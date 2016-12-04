package com.imaginarycode.minecraft.redisbungee.manager;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import com.google.common.collect.ImmutableList;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;

import de.pesacraft.shares.config.CustomRedisTemplate;
import lombok.Getter;
import lombok.NonNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

@Component
public class ServerManager implements InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private CustomRedisTemplate redisTemplate;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	@Getter
	private volatile List<String> serverIds;
	private final AtomicInteger nagAboutServers = new AtomicInteger();

	@Override
	public void afterPropertiesSet() throws Exception {
		updateServerIds();
	}

	public boolean existsServer(String serverId) {
		return getServerIds().contains(serverId);
	}

	public void updateServerIds() {
		serverIds = getCurrentServerIds(true, false);
	}

	public List<String> getCurrentServerIds(boolean nag, boolean lagged) {
		long time = redisTemplate.execute(RedisConnection::time);

		int nagTime = 0;

		if (nag) {
			nagTime = nagAboutServers.decrementAndGet();
			if (nagTime <= 0) {
				nagAboutServers.set(10);
			}
		}

		ImmutableList.Builder<String> servers = ImmutableList.builder();
		Map<String, String> heartbeats = hashOperations.entries("heartbeats");

		for (Map.Entry<String, String> entry : heartbeats.entrySet()) {
			try {
				long stamp = Long.parseLong(entry.getValue());
				if (lagged ? time >= stamp + 30 : time <= stamp + 30)
					servers.add(entry.getKey());
				else if (nag && nagTime <= 0) {
					plugin.getLogger().severe(entry.getKey() + " is " + (time - stamp) + " seconds behind! (Time not synchronized or server down?)");
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return servers.build();

	}

	public final void sendProxyCommand(@NonNull String proxyId, @NonNull String command) {
		checkArgument(getServerIds().contains(proxyId) || proxyId.equals("allservers"), "proxyId is invalid");
		sendChannelMessage("redisbungee-" + proxyId, command);
	}

	public final void sendChannelMessage(String channel, String message) {
		redisTemplate.convertAndSend(channel, message);
	}
}
