package com.imaginarycode.minecraft.redisbungee.listener;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCore;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;
import com.imaginarycode.minecraft.redisbungee.manager.DataManager;
import com.imaginarycode.minecraft.redisbungee.manager.ServerManager;
import com.imaginarycode.minecraft.redisbungee.util.RedisCallable;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDTranslator;

import de.pesacraft.shares.config.CustomRedisTemplate;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

@Component
public class PlayerConnectionListener implements Listener, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private ServerManager serverManager;

	@Autowired
	private CustomRedisTemplate redisTemplate;

	@Resource(name = "redisTemplate")
	private SetOperations<String, String> setOperations;

	@Autowired
	private UUIDTranslator uuidTranslator;

	@Autowired
	private RedisUtil redisUtil;

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
		invalidate(event.getPlayer().getUniqueId());

		plugin.getProxy().getScheduler().runAsync(plugin, () -> {
			redisTemplate.convertAndSend("redisbungee-data", new DataManager.DataManagerMessage<>(
					event.getPlayer().getUniqueId(), DataManager.DataManagerMessage.Action.JOIN,
					new DataManager.LoginPayload(event.getPlayer().getAddress().getAddress()))));
		});
	}

	@EventHandler
	public void onPlayerDisconnect(final PlayerDisconnectEvent event) {
		// Invalidate all entries related to this player, since they now lie.
		invalidate(event.getPlayer().getUniqueId());

		plugin.getProxy().getScheduler().runAsync(plugin, () -> {
			redisUtil.cleanUpPlayer(event.getPlayer().getUniqueId().toString());
		});
	}
}
