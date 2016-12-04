package com.imaginarycode.minecraft.redisbungee.repetitive;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.stereotype.Component;

import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.manager.PlayerManager;
import com.imaginarycode.minecraft.redisbungee.manager.ServerManager;

import de.pesacraft.bungee.core.server.ServerInformation;
import de.pesacraft.shares.config.CustomRedisTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

@Component
public class HeartbeatTask implements Runnable, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private CustomRedisTemplate redisTemplate;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	@Autowired
	private ServerInformation serverInformation;

	@Autowired
	private PlayerManager playerManager;

	@Autowired
	private ServerManager serverManager;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getScheduler().schedule(plugin, this, 0, 3, TimeUnit.SECONDS);
	}

	@Override
	public void run() {
		long redisTime = redisTemplate.execute(RedisConnection::time);
		hashOperations.put("heartbeats", serverInformation.getServerName(), String.valueOf(redisTime));

		try {
			serverManager.updateServerIds();
			playerManager.refreshPlayerCount();
		} catch (Throwable e) {
			plugin.getLogger().log(Level.SEVERE, "Unable to update data - did your Redis server go away?", e);
		}
	}
}
