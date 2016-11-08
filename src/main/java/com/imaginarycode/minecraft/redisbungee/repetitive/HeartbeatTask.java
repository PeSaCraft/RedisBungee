package com.imaginarycode.minecraft.redisbungee.repetitive;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.imaginarycode.minecraft.redisbungee.RedisBungee;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

@Component
public class HeartbeatTask implements Runnable, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getScheduler().schedule(plugin, this, 0, 3, TimeUnit.SECONDS);
	}

	@Override
	public void run() {
		try (Jedis rsc = pool.getResource()) {
			long redisTime = getRedisTime(rsc.time());
			rsc.hset("heartbeats", configuration.getServerId(), String.valueOf(redisTime));
		} catch (JedisConnectionException e) {
			// Redis server has disappeared!
			getLogger().log(Level.SEVERE, "Unable to update heartbeat - did your Redis server go away?", e);
			return;
		}
		try {
			serverIds = getCurrentServerIds(true, false);
			globalPlayerCount.set(getCurrentCount());
		} catch (Throwable e) {
			getLogger().log(Level.SEVERE, "Unable to update data - did your Redis server go away?", e);
		}
	}
}
