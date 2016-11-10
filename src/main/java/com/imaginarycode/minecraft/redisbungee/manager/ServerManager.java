package com.imaginarycode.minecraft.redisbungee.manager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import com.google.common.collect.ImmutableList;

import de.pesacraft.shares.config.CustomRedisTemplate;
import lombok.Getter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

@Component
public class ServerManager {

	@Autowired
	private RedisServerCommands redisServerCommands;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	@Getter
	private volatile List<String> serverIds;
	private final AtomicInteger nagAboutServers = new AtomicInteger();

	public boolean existsServer(String serverId) {
		return getServerIds().contains(serverId);
	}

	public void updateServerIds() {
		serverIds = getCurrentServerIds(true, false);
	}

	private List<String> getCurrentServerIds(boolean nag, boolean lagged) {
		long time = redisServerCommands.time();

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
					getLogger().severe(entry.getKey() + " is " + (time - stamp) + " seconds behind! (Time not synchronized or server down?)");
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return servers.build();

	}
}
