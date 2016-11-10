package com.imaginarycode.minecraft.redisbungee;

import com.google.common.annotations.VisibleForTesting;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;

import de.pesacraft.shares.config.CustomRedisTemplate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.LobbyGameCategoryInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

@VisibleForTesting
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Component
public class RedisUtil {

	@Autowired
	private CustomRedisTemplate redisTemplate;

	@Resource(name = "redisTemplate")
	private HashOperations<String, String, String> hashOperations;

	@Resource(name = "redisTemplate")
	private SetOperations<String, String> setOperations;

    public void createPlayer(ProxiedPlayer player, boolean fireEvent) {
        createPlayer(player.getPendingConnection(), fireEvent);
        if (player.getServer() != null) {
        	String uuid = player.getUniqueId().toString();
        	ServerInfo server = player.getServer().getInfo();
        	String serverName = server.getName();
        	String categoryKey = "category:" + server.getCategory().getName();

            hashOperations.put("player:" + uuid, "server", serverName);
            setOperations.add("server:" + serverName + ":usersOnline", uuid);
            setOperations.add(categoryKey + ":usersOnline", uuid);

            int players;
            switch (server.getServerType()) {
			case DIRECT: case LOBBY:
				players = pipeline.zincrby(categoryKey + ":servers", 1, serverName).get().intValue();
				server.getCategory().putServer(server, players);
				break;
			case GAME:
				String map = server.getMap();
				players = pipeline.zincrby(categoryKey + ":map:" + map, 1, serverName).get().intValue();
				((LobbyGameCategoryInfo) server.getCategory()).putGameServer(server.getMap(), server, players);
				break;
			default:
				break;
            }
        }
    }

    public void createPlayer(PendingConnection connection, boolean fireEvent) {
        Map<String, String> playerData = new HashMap<>(4);
        playerData.put("online", "0");
        playerData.put("ip", connection.getAddress().getAddress().getHostAddress());
        playerData.put("proxy", RedisBungeeCore.getConfiguration().getServerId());
        playerData.put("name", connection.getName());

        setOperations.add("proxy:" + RedisBungeeCore.getApi().getServerId() + ":usersOnline", connection.getUniqueId().toString());
        hashOperations.putAll("player:" + connection.getUniqueId().toString(), playerData);

        if (fireEvent) {
            redisTemplate.convertAndSend("redisbungee-data", RedisBungeeCore.getGson().toJson(new CachedDataManager.DataManagerMessage<>(
                    connection.getUniqueId(), CachedDataManager.DataManagerMessage.Action.JOIN,
                    new CachedDataManager.LoginPayload(connection.getAddress().getAddress()))));
        }
    }

    public void cleanUpPlayer(String player) {
    	setOperations.remove("proxy:" + RedisBungeeCore.getApi().getServerId() + ":usersOnline", player);
    	hashOperations.delete("player:" + player, "server", "ip", "proxy");
        ServerInfo server = ProxyServer.getInstance().getServerInfo(
        		hashOperations.get("player:" + player, "server"));
        if (server != null) {
        	setOperations.remove("server:" + server.getName() + ":usersOnline", player);
        	setOperations.remove("category:" + server.getCategory().getName() + ":usersOnline", player);
	        int players;
	        switch (server.getServerType()) {
			case DIRECT: case LOBBY:
				players = rsc.zincrby("category:" + server.getCategory().getName() + ":servers", -1, server.getName()).intValue();
				server.getCategory().putServer(server, players);
				break;
			case GAME:
				String map = server.getMap();
				players = rsc.zincrby("category:" + server.getCategory().getName() + ":map:" + map, -1, server.getName()).intValue();
				((LobbyGameCategoryInfo) server.getCategory()).putGameServer(server.getMap(), server, players);
				break;
			default:
				break;
	        }
        }
        long timestamp = System.currentTimeMillis();
        hashOperations.put("player:" + player, "online", String.valueOf(timestamp));
        redisTemplate.convertAndSend("redisbungee-data", RedisBungeeCore.getGson().toJson(new CachedDataManager.DataManagerMessage<>(
                UUID.fromString(player), CachedDataManager.DataManagerMessage.Action.LEAVE,
                new CachedDataManager.LogoutPayload(timestamp))));
    }

    public static boolean canUseLua(String redisVersion) {
        // Need to use >=2.6 to use Lua optimizations.
        String[] args = redisVersion.split("\\.");

        if (args.length < 2) {
            return false;
        }

        int major = Integer.parseInt(args[0]);
        int minor = Integer.parseInt(args[1]);

        return major >= 3 || (major == 2 && minor >= 6);
    }
}
