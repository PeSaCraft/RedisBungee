package com.imaginarycode.minecraft.redisbungee.listener;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.ScriptSource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import com.google.gson.JsonParser;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCommandSender;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;
import com.imaginarycode.minecraft.redisbungee.util.IOUtil;

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
	public void onSpringContextInitialized(SpringContextStartedEvent event) {
		File crashFile = new File(plugin.getDataFolder(), "restarted_from_crash.txt");

		if (crashFile.exists()) {
			crashFile.delete();
		}
		else if (hashOperations.hasKey("heartbeats", serverInformation.getServerName())) {
			try {
				long value = Long.parseLong(hashOperations.get("heartbeats", serverInformation.getServerName()));
				if (System.currentTimeMillis() < value + 20000) {
					plugin.getLogger().severe("You have launched a possible impostor BungeeCord instance. Another instance is already running.");
					plugin.getLogger().severe("For data consistency reasons, RedisBungee will now disable itself.");
					plugin.getLogger().severe("If this instance is coming up from a crash, create a file in your RedisBungee plugins directory with the name 'restarted_from_crash.txt' and RedisBungee will not perform this check.");
					throw new RuntimeException("Possible impostor instance!");
				}
			} catch (NumberFormatException ignored) {}
		}


		hashOperations.put("heartbeats", serverInformation.getServerName(), String.valueOf(System.currentTimeMillis()));

		long uuidCacheSize = hashOperations.size("uuid-cache");
		if (uuidCacheSize > 750000) {
			plugin.getLogger().info("Looks like you have a really big UUID cache! Run https://www.spigotmc.org/resources/redisbungeecleaner.8505/ as soon as possible.");
		}


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
