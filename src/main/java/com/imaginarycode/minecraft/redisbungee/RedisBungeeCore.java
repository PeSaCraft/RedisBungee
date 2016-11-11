package com.imaginarycode.minecraft.redisbungee;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.*;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;
import com.imaginarycode.minecraft.redisbungee.util.*;
import com.imaginarycode.minecraft.redisbungee.util.uuid.NameFetcher;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDFetcher;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDTranslator;

import de.pesacraft.bungee.core.PeSaCraftBungeeCore;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.aspectj.EnableSpringConfigured;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The RedisBungee plugin.
 * <p>
 * The only function of interest is {@link #getApi()}, which exposes some functions in this class.
 */
@Configuration
@ComponentScan
@EnableSpringConfigured
@Import(PeSaCraftBungeeCore.class)
public final class RedisBungeeCore {

	@Bean
	public RedisBungee redisBungee() {
		return (RedisBungee) ProxyServer.getInstance().getPluginManager().getPlugin("RedisBungee");
	}
	@Bean
	public Gson gson() {
		return new Gson();
	}

	@Override
	public void onEnable() {
		File crashFile = new File(getDataFolder(), "restarted_from_crash.txt");
		if (crashFile.exists()) {
			crashFile.delete();
		} else if (rsc.hexists("heartbeats", serverId)) {
			try {
				long value = Long.parseLong(rsc.hget("heartbeats", serverId));
				if (System.currentTimeMillis() < value + 20000) {
					getLogger().severe("You have launched a possible impostor BungeeCord instance. Another instance is already running.");
					getLogger().severe("For data consistency reasons, RedisBungee will now disable itself.");
					getLogger().severe("If this instance is coming up from a crash, create a file in your RedisBungee plugins directory with the name 'restarted_from_crash.txt' and RedisBungee will not perform this check.");
					throw new RuntimeException("Possible impostor instance!");
				}
			} catch (NumberFormatException ignored) {
			}
		}

		try (Jedis tmpRsc = pool.getResource()) {
			// This is more portable than INFO <section>
			String info = tmpRsc.info();
			for (String s : info.split("\r\n")) {
				if (s.startsWith("redis_version:")) {
					String version = s.split(":")[1];
					if (!(usingLua = RedisUtil.canUseLua(version))) {
						getLogger().warning("Your version of Redis (" + version + ") is not at least version 2.6. RedisBungee requires a newer version of Redis.");
						throw new RuntimeException("Unsupported Redis version detected");
					} else {
						LuaManager manager = new LuaManager(this);
						getPlayerCountScript = manager.createScript(IOUtil.readInputStreamAsString(getResourceAsStream("lua/get_player_count.lua")));
					}
					break;
				}
			}

			tmpRsc.hset("heartbeats", configuration.getServerId(), String.valueOf(System.currentTimeMillis()));

			long uuidCacheSize = tmpRsc.hlen("uuid-cache");
			if (uuidCacheSize > 750000) {
				getLogger().info("Looks like you have a really big UUID cache! Run https://www.spigotmc.org/resources/redisbungeecleaner.8505/ as soon as possible.");
			}
		}

		registerCommands();

		getProxy().registerChannel("RedisBungee");
	}
}
