package com.imaginarycode.minecraft.redisbungee;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.*;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.util.*;
import com.imaginarycode.minecraft.redisbungee.util.uuid.NameFetcher;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDFetcher;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDTranslator;
import com.squareup.okhttp.Dispatcher;
import com.squareup.okhttp.OkHttpClient;

import de.pesacraft.bungee.core.event.spring.SpringContextInitializationEvent;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The RedisBungee plugin.
 * <p>
 * The only function of interest is {@link #getApi()}, which exposes some functions in this class.
 */
public final class RedisBungee extends Plugin implements Listener {

	@Override
	public void onEnable() {
	   getProxy().getPluginManager().registerListener(this, this);
	}

	@Override
	public void onDisable() {
		if (pool != null) {
			// Poison the PubSub listener
			psl.poison();
			integrityCheck.cancel(true);
			heartbeatTask.cancel(true);
			getProxy().getPluginManager().unregisterListeners(this);

			try (Jedis tmpRsc = pool.getResource()) {
				tmpRsc.hdel("heartbeats", configuration.getServerId());
				if (tmpRsc.scard("proxy:" + configuration.getServerId() + ":usersOnline") > 0) {
					Set<String> players = tmpRsc.smembers("proxy:" + configuration.getServerId() + ":usersOnline");
					for (String member : players)
						RedisUtil.cleanUpPlayer(member, tmpRsc);
				}
			}

			pool.destroy();
		}
	}

	@EventHandler
	public void onSpringContextInitialization(SpringContextInitializationEvent event) {
		event.addClassesToLoad(classes);
	}
}
