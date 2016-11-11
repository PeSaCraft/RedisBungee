package com.imaginarycode.minecraft.redisbungee.repetitive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;
import com.imaginarycode.minecraft.redisbungee.manager.PlayerManager;
import com.imaginarycode.minecraft.redisbungee.manager.ServerManager;

import de.pesacraft.bungee.core.server.ServerInformation;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

@Component
public class IntegrityCheck implements Runnable, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private PlayerManager playerManager;

	@Autowired
	private ServerManager serverManager;

	@Resource(name = "redisTemplate")
	private SetOperations<String, String> setOperations;

	@Autowired
	private ServerInformation serverInformation;

	@Autowired
	private RedisUtil redisUtil;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getScheduler().schedule(plugin, this, 0, 1, TimeUnit.MINUTES);
	}

	@Override
	public void run() {
		Set<String> players = playerManager.getLocalPlayersAsUuidStrings();
		Set<String> playersInRedis = setOperations.members("proxy:" + serverInformation.getServerName() + ":usersOnline");
		List<String> lagged = serverManager.getCurrentServerIds(false, true);

		// Clean up lagged players.
		for (String s : lagged) {
			Set<String> laggedPlayers = setOperations.members("proxy:" + s + ":usersOnline");
			setOperations.getOperations().delete("proxy:" + s + ":usersOnline");
			if (!laggedPlayers.isEmpty()) {
				plugin.getLogger().info("Cleaning up lagged proxy " + s + " (" + laggedPlayers.size() + " players)...");
				for (String laggedPlayer : laggedPlayers) {
					redisUtil.cleanUpPlayer(laggedPlayer);
				}
			}
		}

		Set<String> absentLocally = new HashSet<>(playersInRedis);
		absentLocally.removeAll(players);
		Set<String> absentInRedis = new HashSet<>(players);
		absentInRedis.removeAll(playersInRedis);

		for (String member : absentLocally) {
			boolean found = false;
			for (String proxyId : serverManager.getServerIds()) {
				if (proxyId.equals(serverInformation.getServerName())) continue;
				if (setOperations.isMember("proxy:" + proxyId + ":usersOnline", member)) {
					// Just clean up the set.
					found = true;
					break;
				}
			}
			if (!found) {
				redisUtil.cleanUpPlayer(member);
				plugin.getLogger().warning("Player found in set that was not found locally and globally: " + member);
			} else {
				setOperations.remove("proxy:" + serverInformation.getServerName() + ":usersOnline", member);
				plugin.getLogger().warning("Player found in set that was not found locally, but is on another proxy: " + member);
			}
		}

		for (String player : absentLocally) {
			// Player not online according to Redis but not BungeeCord.
			plugin.getLogger().warning("Player " + player + " is on the proxy but not in Redis.");

			ProxiedPlayer proxiedPlayer = ProxyServer.getInstance().getPlayer(UUID.fromString(player));
			if (proxiedPlayer == null)
				continue; // We'll deal with it later.

			redisUtil.createPlayer(proxiedPlayer, true);
		}
	}
}
