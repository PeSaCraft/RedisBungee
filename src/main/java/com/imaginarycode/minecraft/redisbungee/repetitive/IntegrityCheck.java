package com.imaginarycode.minecraft.redisbungee.repetitive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

public class IntegrityCheck implements Runnable, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getScheduler().schedule(plugin, this, 0, 1, TimeUnit.MINUTES);
	}

	@Override
	public void run() {
		try (Jedis tmpRsc = pool.getResource()) {
			Set<String> players = getLocalPlayersAsUuidStrings();
			Set<String> playersInRedis = tmpRsc.smembers("proxy:" + configuration.getServerId() + ":usersOnline");
			List<String> lagged = getCurrentServerIds(false, true);

			// Clean up lagged players.
			for (String s : lagged) {
				Set<String> laggedPlayers = tmpRsc.smembers("proxy:" + s + ":usersOnline");
				tmpRsc.del("proxy:" + s + ":usersOnline");
				if (!laggedPlayers.isEmpty()) {
					getLogger().info("Cleaning up lagged proxy " + s + " (" + laggedPlayers.size() + " players)...");
					for (String laggedPlayer : laggedPlayers) {
						RedisUtil.cleanUpPlayer(laggedPlayer, tmpRsc);
					}
				}
			}

			Set<String> absentLocally = new HashSet<>(playersInRedis);
			absentLocally.removeAll(players);
			Set<String> absentInRedis = new HashSet<>(players);
			absentInRedis.removeAll(playersInRedis);

			for (String member : absentLocally) {
				boolean found = false;
				for (String proxyId : getServerIds()) {
					if (proxyId.equals(configuration.getServerId())) continue;
					if (tmpRsc.sismember("proxy:" + proxyId + ":usersOnline", member)) {
						// Just clean up the set.
						found = true;
						break;
					}
				}
				if (!found) {
					RedisUtil.cleanUpPlayer(member, tmpRsc);
					getLogger().warning("Player found in set that was not found locally and globally: " + member);
				} else {
					tmpRsc.srem("proxy:" + configuration.getServerId() + ":usersOnline", member);
					getLogger().warning("Player found in set that was not found locally, but is on another proxy: " + member);
				}
			}

			Pipeline pipeline = tmpRsc.pipelined();

			for (String player : absentLocally) {
				// Player not online according to Redis but not BungeeCord.
				getLogger().warning("Player " + player + " is on the proxy but not in Redis.");

				ProxiedPlayer proxiedPlayer = ProxyServer.getInstance().getPlayer(UUID.fromString(player));
				if (proxiedPlayer == null)
					continue; // We'll deal with it later.

				RedisUtil.createPlayer(proxiedPlayer, pipeline, true);
			}

			pipeline.sync();
		} catch (Throwable e) {
			getLogger().log(Level.SEVERE, "Unable to fix up stored player data", e);
		}
	}
}
