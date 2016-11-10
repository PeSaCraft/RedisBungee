package com.imaginarycode.minecraft.redisbungee.listener;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeAPI;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeConfiguration;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCore;
import com.imaginarycode.minecraft.redisbungee.RedisUtil;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;
import com.imaginarycode.minecraft.redisbungee.manager.PlayerManager;
import com.imaginarycode.minecraft.redisbungee.manager.ServerManager;
import com.imaginarycode.minecraft.redisbungee.util.RedisCallable;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDTranslator;

import de.pesacraft.bungee.core.server.ServerInformation;
import de.pesacraft.shares.config.CustomRedisTemplate;
import lombok.AllArgsConstructor;
import net.md_5.bungee.api.AbstractReconnectHandler;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
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

import javax.annotation.Resource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Component;

@Component
public class ManagmentListener implements Listener, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private ServerInformation serverInformation;

	@Autowired
	private RedisBungeeConfiguration redisBungeeConfiguration;

	@Autowired
	private PlayerManager playerManager;

	@Autowired
	private RedisBungeeAPI api;

	@Autowired
	private UUIDTranslator uuidTranslator;

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getPluginManager().registerListener(plugin, this);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPing(final ProxyPingEvent event) {
		if (redisBungeeConfiguration.getExemptAddresses().contains(event.getConnection().getAddress().getAddress())) {
			return;
		}

		ServerInfo forced = AbstractReconnectHandler.getForcedHost(event.getConnection());

		if (forced != null && event.getConnection().getListener().isPingPassthrough()) {
			return;
		}

		event.getResponse().getPlayers().setOnline(playerManager.getCount());
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
								original = playerManager.getPlayers();
							} else {
								try {
									original = api.getPlayersOnServer(type);
								} catch (IllegalArgumentException ignored) {
								}
							}
							Set<String> players = new HashSet<>();
							for (UUID uuid : original)
								players.add(uuidTranslator.getNameFromUuid(uuid, false));
							out.writeUTF(Joiner.on(',').join(players));
							break;
						case "PlayerCount":
							out.writeUTF("PlayerCount");
							type = in.readUTF();
							if (type.equals("ALL")) {
								out.writeUTF("ALL");
								out.writeInt(playerManager.getCount());
							} else {
								out.writeUTF(type);
								try {
									out.writeInt(api.getPlayersOnServer(type).size());
								} catch (IllegalArgumentException e) {
									out.writeInt(0);
								}
							}
							break;
						case "LastOnline":
							String user = in.readUTF();
							out.writeUTF("LastOnline");
							out.writeUTF(user);
							out.writeLong(api.getLastOnline(uuidTranslator.getTranslatedUuid(user, true)));
							break;
						case "ServerPlayers":
							String type1 = in.readUTF();
							out.writeUTF("ServerPlayers");
							Multimap<String, UUID> multimap = api.getServerToPlayers();

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
									human.put(entry.getKey(), uuidTranslator.getNameFromUuid(entry.getValue(), false));
								}
								serializeMultimap(human, true, out);
							} else {
								serializeMultiset(multimap.keys(), out);
							}
							break;
						case "Proxy":
							out.writeUTF("Proxy");
							out.writeUTF(serverInformation.getServerName());
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
