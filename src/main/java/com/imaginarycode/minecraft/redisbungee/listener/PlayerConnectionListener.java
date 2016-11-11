package com.imaginarycode.minecraft.redisbungee.listener;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import com.google.gson.Gson;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCore;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;
import com.imaginarycode.minecraft.redisbungee.manager.ServerManager;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDTranslator;

import de.pesacraft.shares.config.CustomRedisTemplate;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

@Component
public class PlayerConnectionListener implements Listener, InitializingBean {

	private static final BaseComponent[] ALREADY_LOGGED_IN =
			new ComponentBuilder("You are already logged on to this server.").color(ChatColor.RED)
					.append("\n\nIt may help to try logging in again in a few minutes.\nIf this does not resolve your issue, please contact staff.")
					.color(ChatColor.GRAY)
					.create();
	private static final BaseComponent[] ONLINE_MODE_RECONNECT =
			new ComponentBuilder("Whoops! You need to reconnect.").color(ChatColor.RED)
					.append("\n\nWe found someone online using your username. They were kicked and you may reconnect.\nIf this does not work, please contact staff.")
					.color(ChatColor.GRAY)
					.create();

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private ServerManager serverManager;

	@Autowired
	private CustomRedisTemplate redisTemplate;

	@Resource(name = "redisTemplate")
	private SetOperations<String, String> setOperations;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	@Autowired
	private UUIDTranslator uuidTranslator;

	@Autowired
	private RedisUtil redisUtil;

	@Autowired
	private CachedDataManager cachedDataManager;

	@Autowired
	private Gson gson;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getPluginManager().registerListener(plugin, this);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onLogin(final LoginEvent event) {
		event.registerIntent(plugin);
		plugin.getProxy().getScheduler().runAsync(plugin, () -> {
			if (event.isCancelled()) {
				return;
			}

			// We make sure they aren't trying to use an existing player's name.
			// This is problematic for online-mode servers as they always disconnect old clients.
			if (plugin.getProxy().getConfig().isOnlineMode()) {
				ProxiedPlayer player = plugin.getProxy().getPlayer(event.getConnection().getName());

				if (player != null) {
					event.setCancelled(true);
					// TODO: Make it accept a BaseComponent[] like everything else.
					event.setCancelReason(TextComponent.toLegacyText(ONLINE_MODE_RECONNECT));
					return;
				}
			}

			for (String s : serverManager.getServerIds()) {
				if (setOperations.isMember("proxy:" + s + ":usersOnline", event.getConnection().getUniqueId().toString())) {
					event.setCancelled(true);
					// TODO: Make it accept a BaseComponent[] like everything else.
					event.setCancelReason(TextComponent.toLegacyText(ALREADY_LOGGED_IN));
					return;
				}
			}

			uuidTranslator.persistInfo(event.getConnection().getName(), event.getConnection().getUniqueId());
			redisUtil.createPlayer(event.getConnection(), false);
			// We're not publishing, the API says we only publish at PostLoginEvent time.
		});
	}

	@EventHandler
	public void onPostLogin(final PostLoginEvent event) {
		// Invalidate all entries related to this player, since they now lie.
		cachedDataManager.invalidate(event.getPlayer().getUniqueId());

		plugin.getProxy().getScheduler().runAsync(plugin, () -> {
			redisTemplate.convertAndSend("redisbungee-data", new CachedDataManager.DataManagerMessage<>(
					event.getPlayer().getUniqueId(), CachedDataManager.DataManagerMessage.Action.JOIN,
					new CachedDataManager.LoginPayload(event.getPlayer().getAddress().getAddress())));
		});
	}

	@EventHandler
	public void onPlayerDisconnect(final PlayerDisconnectEvent event) {
		// Invalidate all entries related to this player, since they now lie.
		cachedDataManager.invalidate(event.getPlayer().getUniqueId());

		plugin.getProxy().getScheduler().runAsync(plugin, () -> {
			redisUtil.cleanUpPlayer(event.getPlayer().getUniqueId().toString());
		});
	}

	@EventHandler
	public void onServerChange(final ServerConnectedEvent event) {
		final ServerInfo currentServer = event.getPlayer().getServer() == null ? null : event.getPlayer().getServer().getInfo();
		final ServerInfo newServer = event.getServer().getInfo();

		if (currentServer == null) {
			// joining network
			final String newServerName = newServer.getName();
			final String uuid = event.getPlayer().getUniqueId().toString();

			plugin.getProxy().getScheduler().runAsync(plugin, () -> {
				hashOperations.put("player:" + uuid, "server", newServerName);
				setOperations.add("server:" + newServerName + ":usersOnline", uuid);

//				jedis.sadd("category:" + newServer.getCategory().getName() + ":usersOnline", uuid);
//
//				int players;
//				switch (newServer.getServerType()) {
//				case DIRECT: case LOBBY:
//					players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":servers", 1, newServerName).intValue();
//					newServer.getCategory().putServer(newServer, players);
//					break;
//				case GAME:
//					String map = newServer.getMap();
//					players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":map:" + map, 1, newServerName).intValue();
//					((LobbyGameCategoryInfo) newServer.getCategory()).putGameServer(newServer.getMap(), newServer, players);
//					break;
//				default:
//					break;
//				}
				redisTemplate.convertAndSend("redisbungee-data", gson.toJson(new CachedDataManager.DataManagerMessage<>(
						event.getPlayer().getUniqueId(), CachedDataManager.DataManagerMessage.Action.SERVER_CHANGE,
						new CachedDataManager.ServerChangePayload(newServerName, null))));
			});
		}
		else {
			// switching server
			final String currentServerName = currentServer.getName();
			final String newServerName = newServer.getName();
			final String uuid = event.getPlayer().getUniqueId().toString();

			plugin.getProxy().getScheduler().runAsync(plugin, () -> {
				hashOperations.put("player:" + uuid, "server", newServerName);
				setOperations.remove("server:" + currentServerName + ":usersOnline", uuid);
				setOperations.add("server:" + newServerName + ":usersOnline", uuid);

//				if (newServer.getCategory() != currentServer.getCategory()) {
//					jedis.srem("category:" + currentServer.getCategory().getName() + ":usersOnline", uuid);
//					jedis.sadd("category:" + newServer.getCategory().getName() + ":usersOnline", uuid);
//				}
//
//				int players;
//				switch (currentServer.getServerType()) {
//				case DIRECT: case LOBBY:
//					players = jedis.zincrby("category:" + currentServer.getCategory().getName() + ":servers", -1, currentServerName).intValue();
//					currentServer.getCategory().putServer(currentServer, players);
//					break;
//				case GAME:
//					String map = currentServer.getMap();
//					players = jedis.zincrby("category:" + currentServer.getCategory().getName() + ":map:" + map, -1, currentServerName).intValue();
//					((LobbyGameCategoryInfo) currentServer.getCategory()).putGameServer(currentServer.getMap(), currentServer, players);
//					break;
//				default:
//					break;
//				}
//				switch (newServer.getServerType()) {
//				case DIRECT: case LOBBY:
//					players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":servers", 1, newServerName).intValue();
//					newServer.getCategory().putServer(newServer, players);
//					break;
//				case GAME:
//					String map = newServer.getMap();
//					players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":map:" + map, 1, newServerName).intValue();
//					((LobbyGameCategoryInfo) newServer.getCategory()).putGameServer(newServer.getMap(), newServer, players);
//					break;
//				default:
//					break;
//				}
				redisTemplate.convertAndSend("redisbungee-data", gson.toJson(new CachedDataManager.DataManagerMessage<>(
						event.getPlayer().getUniqueId(), CachedDataManager.DataManagerMessage.Action.SERVER_CHANGE,
						new CachedDataManager.ServerChangePayload(newServerName, currentServerName))));
			});
		}
	}
}
