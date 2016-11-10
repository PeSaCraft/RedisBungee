package com.imaginarycode.minecraft.redisbungee;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;
import com.imaginarycode.minecraft.redisbungee.util.RedisCallable;
import lombok.AllArgsConstructor;
import net.md_5.bungee.api.AbstractReconnectHandler;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.CategoryInfo;
import net.md_5.bungee.api.config.LobbyGameCategoryInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.net.InetAddress;
import java.util.*;

@AllArgsConstructor
public class RedisBungeeListener implements Listener {
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
    private final RedisBungeeCore plugin;
    private final List<InetAddress> exemptAddresses;

    @EventHandler
    public void onServerChange(final ServerConnectedEvent event) {
    	final ServerInfo currentServer = event.getPlayer().getServer() == null ? null : event.getPlayer().getServer().getInfo();
    	final ServerInfo newServer = event.getServer().getInfo();

    	if (currentServer == null) {
    		// joining network
    		final String newServerName = newServer.getName();
            final String uuid = event.getPlayer().getUniqueId().toString();

            plugin.getProxy().getScheduler().runAsync(plugin, new RedisCallable<Void>(plugin) {
                @Override
                protected Void call(Jedis jedis) {
                	jedis.hset("player:" + uuid, "server", newServerName);
                	jedis.sadd("server:" + newServerName + ":usersOnline", uuid);

                    jedis.sadd("category:" + newServer.getCategory().getName() + ":usersOnline", uuid);

                    int players;
                    switch (newServer.getServerType()) {
        			case DIRECT: case LOBBY:
        				players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":servers", 1, newServerName).intValue();
        				newServer.getCategory().putServer(newServer, players);
        				break;
        			case GAME:
        				String map = newServer.getMap();
        				players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":map:" + map, 1, newServerName).intValue();
        				((LobbyGameCategoryInfo) newServer.getCategory()).putGameServer(newServer.getMap(), newServer, players);
        				break;
        			default:
        				break;
                    }
                	jedis.publish("redisbungee-data", RedisBungeeCore.getGson().toJson(new CachedDataManager.DataManagerMessage<>(
                            event.getPlayer().getUniqueId(), CachedDataManager.DataManagerMessage.Action.SERVER_CHANGE,
                            new CachedDataManager.ServerChangePayload(newServerName, null))));
                    return null;
                }
            });
    	}
    	else {
    		// switching server
    		final String currentServerName = currentServer.getName();
        	final String newServerName = newServer.getName();
            final String uuid = event.getPlayer().getUniqueId().toString();

            plugin.getProxy().getScheduler().runAsync(plugin, new RedisCallable<Void>(plugin) {
                @Override
                protected Void call(Jedis jedis) {
                	jedis.hset("player:" + uuid, "server", newServerName);
                	jedis.srem("server:" + currentServerName + ":usersOnline", uuid);
                    jedis.sadd("server:" + newServerName + ":usersOnline", uuid);

                    if (newServer.getCategory() != currentServer.getCategory()) {
                    	jedis.srem("category:" + currentServer.getCategory().getName() + ":usersOnline", uuid);
                    	jedis.sadd("category:" + newServer.getCategory().getName() + ":usersOnline", uuid);
                    }

                    int players;
                    switch (currentServer.getServerType()) {
        			case DIRECT: case LOBBY:
        				players = jedis.zincrby("category:" + currentServer.getCategory().getName() + ":servers", -1, currentServerName).intValue();
        				currentServer.getCategory().putServer(currentServer, players);
        				break;
        			case GAME:
        				String map = currentServer.getMap();
        				players = jedis.zincrby("category:" + currentServer.getCategory().getName() + ":map:" + map, -1, currentServerName).intValue();
        				((LobbyGameCategoryInfo) currentServer.getCategory()).putGameServer(currentServer.getMap(), currentServer, players);
        				break;
        			default:
        				break;
                    }
                    switch (newServer.getServerType()) {
        			case DIRECT: case LOBBY:
        				players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":servers", 1, newServerName).intValue();
        				newServer.getCategory().putServer(newServer, players);
        				break;
        			case GAME:
        				String map = newServer.getMap();
        				players = jedis.zincrby("category:" + newServer.getCategory().getName() + ":map:" + map, 1, newServerName).intValue();
        				((LobbyGameCategoryInfo) newServer.getCategory()).putGameServer(newServer.getMap(), newServer, players);
        				break;
        			default:
        				break;
                    }
                	jedis.publish("redisbungee-data", RedisBungeeCore.getGson().toJson(new CachedDataManager.DataManagerMessage<>(
                            event.getPlayer().getUniqueId(), CachedDataManager.DataManagerMessage.Action.SERVER_CHANGE,
                            new CachedDataManager.ServerChangePayload(newServerName, currentServerName))));
                    return null;
                }
            });
    	}
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPing(final ProxyPingEvent event) {
        if (exemptAddresses.contains(event.getConnection().getAddress().getAddress())) {
            return;
        }

        CategoryInfo forced = AbstractReconnectHandler.getForcedHost(event.getConnection());

        if (forced != null && event.getConnection().getListener().isPingPassthrough()) {
            return;
        }

        event.getResponse().getPlayers().setOnline(plugin.getCount());
    }

    @EventHandler
    public void onPluginMessage(final PluginMessageEvent event) {
        if (event.getTag().equals("RedisBungee") && event.getSender() instanceof Server) {
            final byte[] data = Arrays.copyOf(event.getData(), event.getData().length);
            plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
                @Override
                public void run() {
                    ByteArrayDataInput in = ByteStreams.newDataInput(data);

                    String subchannel = in.readUTF();
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    String type;

                    switch (subchannel) {
                        case "PlayerList":
                            out.writeUTF("PlayerList");
                            Set<UUID> original = Collections.emptySet();
                            type = in.readUTF();
                            if (type.equals("ALL")) {
                                out.writeUTF("ALL");
                                original = plugin.getPlayers();
                            } else {
                                try {
                                    original = RedisBungeeCore.getApi().getPlayersOnServer(type);
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                            Set<String> players = new HashSet<>();
                            for (UUID uuid : original)
                                players.add(plugin.getUuidTranslator().getNameFromUuid(uuid, false));
                            out.writeUTF(Joiner.on(',').join(players));
                            break;
                        case "PlayerCount":
                            out.writeUTF("PlayerCount");
                            type = in.readUTF();
                            if (type.equals("ALL")) {
                                out.writeUTF("ALL");
                                out.writeInt(plugin.getCount());
                            } else {
                                out.writeUTF(type);
                                try {
                                    out.writeInt(RedisBungeeCore.getApi().getPlayersOnServer(type).size());
                                } catch (IllegalArgumentException e) {
                                    out.writeInt(0);
                                }
                            }
                            break;
                        case "LastOnline":
                            String user = in.readUTF();
                            out.writeUTF("LastOnline");
                            out.writeUTF(user);
                            out.writeLong(RedisBungeeCore.getApi().getLastOnline(plugin.getUuidTranslator().getTranslatedUuid(user, true)));
                            break;
                        case "ServerPlayers":
                            String type1 = in.readUTF();
                            out.writeUTF("ServerPlayers");
                            Multimap<String, UUID> multimap = RedisBungeeCore.getApi().getServerToPlayers();

                            boolean includesUsers;

                            switch (type1) {
                                case "COUNT":
                                    includesUsers = false;
                                    break;
                                case "PLAYERS":
                                    includesUsers = true;
                                    break;
                                default:
                                    // TODO: Should I raise an error?
                                    return;
                            }

                            out.writeUTF(type1);

                            if (includesUsers) {
                                Multimap<String, String> human = HashMultimap.create();
                                for (Map.Entry<String, UUID> entry : multimap.entries()) {
                                    human.put(entry.getKey(), plugin.getUuidTranslator().getNameFromUuid(entry.getValue(), false));
                                }
                                serializeMultimap(human, true, out);
                            } else {
                                serializeMultiset(multimap.keys(), out);
                            }
                            break;
                        case "Proxy":
                            out.writeUTF("Proxy");
                            out.writeUTF(RedisBungeeCore.getConfiguration().getServerId());
                            break;
                        default:
                            return;
                    }

                    ((Server) event.getSender()).sendData("RedisBungee", out.toByteArray());
                }
            });
        }
    }

    private void serializeMultiset(Multiset<String> collection, ByteArrayDataOutput output) {
        output.writeInt(collection.elementSet().size());
        for (Multiset.Entry<String> entry : collection.entrySet()) {
            output.writeUTF(entry.getElement());
            output.writeInt(entry.getCount());
        }
    }

    private void serializeMultimap(Multimap<String, String> collection, boolean includeNames, ByteArrayDataOutput output) {
        output.writeInt(collection.keySet().size());
        for (Map.Entry<String, Collection<String>> entry : collection.asMap().entrySet()) {
            output.writeUTF(entry.getKey());
            if (includeNames) {
                serializeCollection(entry.getValue(), output);
            } else {
                output.writeInt(entry.getValue().size());
            }
        }
    }

    private void serializeCollection(Collection<?> collection, ByteArrayDataOutput output) {
        output.writeInt(collection.size());
        for (Object o : collection) {
            output.writeUTF(o.toString());
        }
    }
}
