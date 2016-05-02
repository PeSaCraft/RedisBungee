package com.imaginarycode.minecraft.redisbungee;

import com.google.common.annotations.VisibleForTesting;
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

@VisibleForTesting
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RedisUtil {
    protected static void createPlayer(ProxiedPlayer player, Pipeline pipeline, boolean fireEvent) {
        createPlayer(player.getPendingConnection(), pipeline, fireEvent);
        if (player.getServer() != null) {
        	String uuid = player.getUniqueId().toString();
        	ServerInfo server = player.getServer().getInfo();
        	String serverName = server.getName();
        	String categoryKey = "category:" + server.getCategory().getName();
        	
            pipeline.hset("player:" + uuid, "server", serverName);
            pipeline.sadd("server:" + serverName + ":usersOnline", uuid);
            pipeline.sadd(categoryKey + ":usersOnline", uuid);
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

    protected static void createPlayer(PendingConnection connection, Pipeline pipeline, boolean fireEvent) {
        Map<String, String> playerData = new HashMap<>(4);
        playerData.put("online", "0");
        playerData.put("ip", connection.getAddress().getAddress().getHostAddress());
        playerData.put("proxy", RedisBungee.getConfiguration().getServerId());

        pipeline.sadd("proxy:" + RedisBungee.getApi().getServerId() + ":usersOnline", connection.getUniqueId().toString());
        pipeline.hmset("player:" + connection.getUniqueId().toString(), playerData);

        if (fireEvent) {
            pipeline.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                    connection.getUniqueId(), DataManager.DataManagerMessage.Action.JOIN,
                    new DataManager.LoginPayload(connection.getAddress().getAddress()))));
        }
    }

    public static void cleanUpPlayer(String player, Jedis rsc) {
    	rsc.srem("proxy:" + RedisBungee.getApi().getServerId() + ":usersOnline", player);
        ServerInfo server = ProxyServer.getInstance().getServerInfo(rsc.hget("player:" + player, "server"));
        rsc.srem("server:" + server.getName() + ":usersOnline", player);
        rsc.srem("category:" + server.getCategory().getName() + ":usersOnline", player);
        rsc.hdel("player:" + player, "server", "ip", "proxy");
        int players;
        switch (server.getServerType()) {
		case DIRECT: case LOBBY:
			players = rsc.zincrby("category:" + server.getCategory().getName() + ":servers", -1, server.getName()).intValue();
			server.getCategory().putServer(server, players);
			break;
		case GAME:
			String map = server.getMap();
			players = rsc.zincrby("category:" + server.getCategory().getName() + ":map:" + map, 1, server.getName()).intValue();
			((LobbyGameCategoryInfo) server.getCategory()).putGameServer(server.getMap(), server, players);
			break;
		default:
			break;
        }
        long timestamp = System.currentTimeMillis();
        rsc.hset("player:" + player, "online", String.valueOf(timestamp));
        rsc.publish("redisbungee-data", RedisBungee.getGson().toJson(new DataManager.DataManagerMessage<>(
                UUID.fromString(player), DataManager.DataManagerMessage.Action.LEAVE,
                new DataManager.LogoutPayload(timestamp))));
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
