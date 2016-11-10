package com.imaginarycode.minecraft.redisbungee.manager;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCore;
import com.imaginarycode.minecraft.redisbungee.events.PlayerChangedServerNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.events.PlayerJoinedNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.events.PlayerLeftNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import redis.clients.jedis.Jedis;

import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

/**
 * This class manages all the data that RedisBungee fetches from Redis, along with updates to that data.
 *
 * @since 0.3.3
 */
@Component
public class CachedDataManager implements Listener {

	@Autowired
	private RedisBungeeCore plugin;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	private final Cache<UUID, String> serverCache = createCache();
	private final Cache<UUID, String> proxyCache = createCache();
	private final Cache<UUID, InetAddress> ipCache = createCache();
	private final Cache<UUID, Long> lastOnlineCache = createCache();

	private static <K, V> Cache<K, V> createCache() {
		// TODO: Allow customization via cache specification, ala ServerListPlus
		return CacheBuilder.newBuilder()
				.maximumSize(1000)
				.expireAfterWrite(1, TimeUnit.HOURS)
				.build();
	}

	public String getServer(final UUID uuid) {
		ProxiedPlayer player = plugin.getProxy().getPlayer(uuid);

		if (player != null)
			return player.getServer() != null ? player.getServer().getInfo().getName() : null;

		try {
			return serverCache.get(uuid, new Callable<String>() {
				@Override
				public String call() throws Exception {
					return Objects.requireNonNull(
							hashOperations.get("player:" + uuid, "server"),
							"user not found");
				}
			});
		} catch (ExecutionException | UncheckedExecutionException e) {
			if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
				return null; // HACK
			plugin.getLogger().log(Level.SEVERE, "Unable to get server", e);
			throw new RuntimeException("Unable to get server for " + uuid, e);
		}
	}

	public String getProxy(final UUID uuid) {
		ProxiedPlayer player = plugin.getProxy().getPlayer(uuid);

		if (player != null)
			return RedisBungeeCore.getConfiguration().getServerId();

		try {
			return proxyCache.get(uuid, new Callable<String>() {
				@Override
				public String call() throws Exception {
					return Objects.requireNonNull(
							hashOperations.get("player:" + uuid, "proxy"),
							"user not found");
				}
			});
		} catch (ExecutionException | UncheckedExecutionException e) {
			if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
				return null; // HACK
			plugin.getLogger().log(Level.SEVERE, "Unable to get proxy", e);
			throw new RuntimeException("Unable to get proxy for " + uuid, e);
		}
	}

	public InetAddress getIp(final UUID uuid) {
		ProxiedPlayer player = plugin.getProxy().getPlayer(uuid);

		if (player != null)
			return player.getAddress().getAddress();

		try {
			return ipCache.get(uuid, new Callable<InetAddress>() {
				@Override
				public InetAddress call() throws Exception {
					return InetAddresses.forString(
							Objects.requireNonNull(
									hashOperations.get("player:" + uuid, "ip"),
									"user not found"));
				}
			});
		} catch (ExecutionException | UncheckedExecutionException e) {
			if (e.getCause() instanceof NullPointerException && e.getCause().getMessage().equals("user not found"))
				return null; // HACK
			plugin.getLogger().log(Level.SEVERE, "Unable to get IP", e);
			throw new RuntimeException("Unable to get IP for " + uuid, e);
		}
	}

	public long getLastOnline(final UUID uuid) {
		ProxiedPlayer player = plugin.getProxy().getPlayer(uuid);

		if (player != null)
			return 0;

		try {
			return lastOnlineCache.get(uuid, new Callable<Long>() {
				@Override
				public Long call() throws Exception {
					String result = hashOperations.get("player:" + uuid, "online");
					return result == null ? -1 : Long.valueOf(result);
				}
			});
		} catch (ExecutionException e) {
			plugin.getLogger().log(Level.SEVERE, "Unable to get last time online", e);
			throw new RuntimeException("Unable to get last time online for " + uuid, e);
		}
	}

	public void invalidate(UUID uuid) {
		ipCache.invalidate(uuid);
		lastOnlineCache.invalidate(uuid);
		serverCache.invalidate(uuid);
		proxyCache.invalidate(uuid);
	}

	public void playerJoined(UUID uuid, String source, InetAddress address) {
		proxyCache.put(uuid, source);
		lastOnlineCache.put(uuid, (long) 0);
		ipCache.put(uuid, address);
	}

	public void playerLeft(UUID uuid, long timestamp) {
		invalidate(uuid);
		lastOnlineCache.put(uuid, timestamp);
	}

	public void playerSwitchedServer(UUID uuid, String server) {
		serverCache.put(uuid, server);
	}

	@Getter
	@RequiredArgsConstructor
	public static class DataManagerMessage<T> {
		private final UUID target;
		private final String source = RedisBungeeCore.getApi().getServerId();
		private final Action action; // for future use!
		private final T payload;

		public enum Action {
			JOIN,
			LEAVE,
			SERVER_CHANGE
		}
	}

	@Getter
	@RequiredArgsConstructor
	public static class LoginPayload {
		private final InetAddress address;
	}

	@Getter
	@RequiredArgsConstructor
	public static class ServerChangePayload {
		private final String server;
		private final String oldServer;
	}

	@Getter
	@RequiredArgsConstructor
	public static class LogoutPayload {
		private final long timestamp;
	}
}
