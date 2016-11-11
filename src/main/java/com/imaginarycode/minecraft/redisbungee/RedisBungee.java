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

import de.pesacraft.bungee.core.event.spring.SpringContextClosingEvent;
import de.pesacraft.bungee.core.event.spring.SpringContextInitializationEvent;
import de.pesacraft.bungee.core.event.spring.SpringContextStartedEvent;
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

import org.springframework.context.ApplicationContext;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The RedisBungee plugin.
 * <p>
 * The only function of interest is {@link #getApi()}, which exposes some functions in this class.
 */
public final class RedisBungee extends Plugin implements Listener {

	private static ApplicationContext context;

	public static RedisBungeeAPI getApi() {
		return context.getBean(RedisBungeeAPI.class);
	}

	@Override
	public void onEnable() {
		getProxy().getPluginManager().registerListener(this, this);
	}

	@Override
	public void onDisable() {
		getProxy().getPluginManager().unregisterListeners(this);
		getProxy().getScheduler().cancel(this);
	}

	@EventHandler
	public void onSpringContextInitialization(SpringContextInitializationEvent event) {
		event.addClassesToLoad(RedisBungeeCore.class);
	}

	@EventHandler
	public void onSpringContextInitialized(SpringContextStartedEvent event) {
		context = event.getApplicationContext();
	}

	public void registerCommands() {
		if (context.getBean(RedisBungeeConfiguration.class).isRegisterBungeeCommands()) {
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.GlistCommand());
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.FindCommand());
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.LastSeenCommand());
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.IpCommand());
		}

		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.SendToAll());
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.ServerId());
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.ServerIds());
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.PlayerProxyCommand());
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.PlistCommand());
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.DebugCommand());

	}
}
