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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;
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

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The RedisBungee plugin.
 * <p>
 * The only function of interest is {@link #getApi()}, which exposes some functions in this class.
 */
public final class RedisBungeeCore {
	@Getter
	private static Gson gson = new Gson();
	private static RedisBungeeAPI api;
	@Getter(AccessLevel.PACKAGE)
	private static PubSubListener psl = null;
	@Getter
	private UUIDTranslator uuidTranslator;
	@Getter(AccessLevel.PACKAGE)
	private static RedisBungeeConfiguration configuration;
	@Getter
	private DataManager dataManager;
	@Getter
	private static OkHttpClient httpClient;

	/**
	 * Fetch the {@link RedisBungeeAPI} object created on plugin start.
	 *
	 * @return the {@link RedisBungeeAPI} object
	 */
	public static RedisBungeeAPI getApi() {
		return api;
	}

	static PubSubListener getPubSubListener() {
		return psl;
	}

	final void sendProxyCommand(@NonNull String proxyId, @NonNull String command) {
		checkArgument(getServerIds().contains(proxyId) || proxyId.equals("allservers"), "proxyId is invalid");
		sendChannelMessage("redisbungee-" + proxyId, command);
	}

	final void sendChannelMessage(String channel, String message) {
		try (Jedis jedis = pool.getResource()) {
			jedis.publish(channel, message);
		} catch (JedisConnectionException e) {
			// Redis server has disappeared!
			getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
			throw new RuntimeException("Unable to publish channel message", e);
		}
	}

	@Override
	public void onEnable() {

		try {
			loadConfig();
		} catch (IOException e) {
			throw new RuntimeException("Unable to load/save config", e);
		} catch (JedisConnectionException e) {
			throw new RuntimeException("Unable to connect to your Redis server!", e);
		}
		if (pool != null) {
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
			serverIds = getCurrentServerIds(true, false);
			uuidTranslator = new UUIDTranslator(this);
			heartbeatTask = jkljkj;
			dataManager = new DataManager(this);
			registerCommands();
			api = new RedisBungeeAPI(this);
			getProxy().getPluginManager().registerListener(this, new RedisBungeeListener(this, configuration.getExemptAddresses()));
			getProxy().getPluginManager().registerListener(this, dataManager);
			psl = new PubSubListener();
			getProxy().getScheduler().runAsync(this, psl);
			integrityCheck = mjkl;
		}
		getProxy().registerChannel("RedisBungee");
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

	private void registerCommands() {
		if (configuration.isRegisterBungeeCommands()) {
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.GlistCommand(this));
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.FindCommand(this));
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.LastSeenCommand(this));
			getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.IpCommand(this));
		}

		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.SendToAll(this));
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.ServerId(this));
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.ServerIds());
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.PlayerProxyCommand(this));
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.PlistCommand(this));
		getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.DebugCommand(this));

	}

	private void loadConfig() throws IOException, JedisConnectionException {
		if (!getDataFolder().exists()) {
			getDataFolder().mkdir();
		}

		File file = new File(getDataFolder(), "config.yml");

		if (!file.exists()) {
			file.createNewFile();
			try (InputStream in = getResourceAsStream("example_config.yml");
				 OutputStream out = new FileOutputStream(file)) {
				ByteStreams.copy(in, out);
			}
		}

		final Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);

		String serverId = configuration.getString("server-id");

		// Configuration sanity checks.
		if (serverId == null || serverId.isEmpty()) {
			throw new RuntimeException("server-id is not specified in the configuration or is empty");
		}

		// Test the connection
		rsc.ping();
		// If that worked, now we can check for an existing, alive Bungee:
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

		FutureTask<Void> task2 = new FutureTask<>(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				httpClient = new OkHttpClient();
				Dispatcher dispatcher = new Dispatcher();
				httpClient.setDispatcher(dispatcher);
				NameFetcher.setHttpClient(httpClient);
				UUIDFetcher.setHttpClient(httpClient);
				RedisBungeeCore.configuration = new RedisBungeeConfiguration(RedisBungeeCore.this.getPool(), configuration);
				return null;
			}
		});

		getProxy().getScheduler().runAsync(this, task2);

		try {
			task2.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException("Unable to create HTTP client", e);
		}

		getLogger().log(Level.INFO, "Successfully connected to Redis.");

	}

	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	class PubSubListener implements Runnable {
		private JedisPubSubHandler jpsh;

		@Override
		public void run() {
			boolean broken = false;
			try (Jedis rsc = pool.getResource()) {
				try {
					jpsh = new JedisPubSubHandler();
					rsc.subscribe(jpsh, "redisbungee-" + configuration.getServerId(), "redisbungee-allservers", "redisbungee-data");
				} catch (Exception e) {
					// FIXME: Extremely ugly hack
					// Attempt to unsubscribe this instance and try again.
					getLogger().log(Level.INFO, "PubSub error, attempting to recover.", e);
					try {
						jpsh.unsubscribe();
					} catch (Exception e1) {
						/* This may fail with
						- java.net.SocketException: Broken pipe
						- redis.clients.jedis.exceptions.JedisConnectionException: JedisPubSub was not subscribed to a Jedis instance
						*/
					}
					broken = true;
				}
			}

			if (broken) {
				run();
			}
		}

		public void addChannel(String... channel) {
			jpsh.subscribe(channel);
		}

		public void removeChannel(String... channel) {
			jpsh.unsubscribe(channel);
		}

		public void poison() {
			jpsh.unsubscribe();
		}
	}

	private class JedisPubSubHandler extends JedisPubSub {
		@Override
		public void onMessage(final String s, final String s2) {
			if (s2.trim().length() == 0) return;
			getProxy().getScheduler().runAsync(RedisBungeeCore.this, new Runnable() {
				@Override
				public void run() {
					getProxy().getPluginManager().callEvent(new PubSubMessageEvent(s, s2));
				}
			});
		}
	}
}
