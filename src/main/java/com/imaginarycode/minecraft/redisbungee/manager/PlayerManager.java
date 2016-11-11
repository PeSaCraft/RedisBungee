package com.imaginarycode.minecraft.redisbungee.manager;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

import de.pesacraft.shares.config.CustomRedisTemplate;
import lombok.NonNull;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

@Component
public class PlayerManager {

	@Autowired
	private CustomRedisTemplate redisTemplate;

	@Resource(name = "redisTemplate")
	private SetOperations<String, String> setOperations;

	@Autowired
	private ServerManager serverManager;

	private final AtomicInteger globalPlayerCount = new AtomicInteger();

	@Autowired
	private RedisScript<Long> playerCountScript;

	private static final Object SERVER_TO_PLAYERS_KEY = new Object();
	private final Cache<Object, Multimap<String, UUID>> serverToPlayersCache = CacheBuilder.newBuilder()
			.expireAfterWrite(5, TimeUnit.SECONDS)
			.build();

	public Set<UUID> getPlayersOnProxy(String server) {
		checkArgument(serverManager.existsServer(server), server + " is not a valid proxy ID");

		Set<String> users = setOperations.members("proxy:" + server + ":usersOnline");

		ImmutableSet.Builder<UUID> builder = ImmutableSet.builder();
		for (String user : users) {
			builder.add(UUID.fromString(user));
		}

		return builder.build();
	}

	public final void refreshPlayerCount() {
		globalPlayerCount.set(getCurrentCount());
	}

	public final Multimap<String, UUID> serversToPlayers() {
		try {
			return serverToPlayersCache.get(SERVER_TO_PLAYERS_KEY, new Callable<Multimap<String, UUID>>() {
				@Override
				public Multimap<String, UUID> call() throws Exception {
					ImmutableMultimap.Builder<String, UUID> builder = ImmutableMultimap.builder();

					for (String server : ProxyServer.getInstance().getServers().keySet()) {
						for (String uuid : setOperations.members("server:" + server + ":usersOnline")) {
							builder.put(server, UUID.fromString(uuid));
						}
					}
					return builder.build();
				}
			});
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public final int getCount() {
		return globalPlayerCount.get();
	}

	final int getCurrentCount() {
		Long count = redisTemplate.execute(playerCountScript, ImmutableList.<String>of(), ImmutableList.<String>of());
		return count.intValue();
	}

	public Set<String> getLocalPlayersAsUuidStrings() {
		ImmutableSet.Builder<String> builder = ImmutableSet.builder();
		for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
			builder.add(player.getUniqueId().toString());
		}
		return builder.build();
	}

	public final Set<UUID> getPlayers() {
		ImmutableSet.Builder<UUID> setBuilder = ImmutableSet.builder();

		List<String> keys = new ArrayList<>();
		for (String i : serverManager.getServerIds()) {
			keys.add("proxy:" + i + ":usersOnline");
		}
		if (!keys.isEmpty()) {
			Set<String> users = setOperations.union(keys.remove(0), keys);
			if (users != null && !users.isEmpty()) {
				for (String user : users) {
					try {
						setBuilder = setBuilder.add(UUID.fromString(user));
					} catch (IllegalArgumentException ignored) {}
				}
			}
		}
		return setBuilder.build();
	}

	final Set<UUID> getPlayersOnServer(@NonNull String server) {
		checkArgument(ProxyServer.getInstance().getServers().containsKey(server), "server does not exist");

		Collection<String> asStrings = setOperations.members("server:" + server + ":usersOnline");

		ImmutableSet.Builder<UUID> builder = ImmutableSet.builder();
		for (String s : asStrings) {
			builder.add(UUID.fromString(s));
		}

		return builder.build();
	}

}
