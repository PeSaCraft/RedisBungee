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
import de.pesacraft.bungee.core.SpringContext;
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.aspectj.EnableSpringConfigured;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static com.google.common.base.Preconditions.checkArgument;

@Configuration
@ComponentScan
@EnableSpringConfigured
@EnableSpringDataWebSupport
@Import(SpringContext.class)
public class RedisBungeeCore {

	@Bean
	public RedisBungee redisBungee() {
		return (RedisBungee) ProxyServer.getInstance().getPluginManager().getPlugin("RedisBungee");
	}

	@Bean
	public Gson gson() {
		return new Gson();
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate(new SimpleClientHttpRequestFactory());
	}

	@Bean
	public RedisScript<Long> playerCountScript(@Autowired RedisBungee plugin) {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setScriptText(
				IOUtil.readInputStreamAsString(
						plugin.getResourceAsStream("lua/get_player_count.lua")));

		script.setResultType(Long.class);

		return script;
	}
}
