package com.imaginarycode.minecraft.redisbungee.listener;

import java.util.Set;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import com.google.gson.JsonParser;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCommandSender;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;

import de.pesacraft.bungee.core.event.spring.SpringContextClosingEvent;
import de.pesacraft.bungee.core.event.spring.SpringContextStartedEvent;
import de.pesacraft.bungee.core.server.ServerInformation;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import redis.clients.jedis.Jedis;

@Component
public class SpringContextListener  implements Listener, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	@Resource(name = "redisTemplate")
	private SetOperations<String, String> setOperations;

	@Autowired
	private ServerInformation serverInformation;

	@Autowired
	private RedisUtil redisUtil;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getPluginManager().registerListener(plugin, this);
	}

	@EventHandler
	public void onSpringContextEnd(SpringContextClosingEvent event) {
		hashOperations.delete("heartbeats", serverInformation.getServerName());
		if (setOperations.size("proxy:" + serverInformation.getServerName() + ":usersOnline") > 0) {
			Set<String> players = setOperations.members("proxy:" + serverInformation.getServerName() + ":usersOnline");
			for (String member : players) {
				redisUtil.cleanUpPlayer(member);
			}
		}
	}
}
