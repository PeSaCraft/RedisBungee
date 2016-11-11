package com.imaginarycode.minecraft.redisbungee;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.imaginarycode.minecraft.redisbungee.manager.ServerManager;
import com.imaginarycode.minecraft.redisbungee.util.uuid.UUIDTranslator;

import de.pesacraft.bungee.core.server.ServerInformation;
import de.pesacraft.shares.config.CustomRedisTemplate;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Command;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

/**
 * This class contains subclasses that are used for the commands RedisBungee overrides or includes: /glist, /find and /lastseen.
 * <p>
 * All classes use the {@link RedisBungeeAPI}.
 *
 * @author tuxed
 * @since 0.2.3
 */
class RedisBungeeCommands {
	private static final BaseComponent[] NO_PLAYER_SPECIFIED =
			new ComponentBuilder("You must specify a player name.").color(ChatColor.RED).create();
	private static final BaseComponent[] PLAYER_NOT_FOUND =
			new ComponentBuilder("No such player found.").color(ChatColor.RED).create();
	private static final BaseComponent[] NO_COMMAND_SPECIFIED =
			new ComponentBuilder("You must specify a command to be run.").color(ChatColor.RED).create();

	private static String playerPlural(int num) {
		return num == 1 ? num + " player is" : num + " players are";
	}


	@Configurable
	public static class GlistCommand extends Command {

		@Autowired
		private RedisBungee plugin;

		@Autowired
		private RedisBungeeAPI api;

		@Autowired
		private UUIDTranslator uuidTranslator;

		GlistCommand() {
			super("glist", "bungeecord.command.list", "redisbungee", "rglist");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
				@Override
				public void run() {
					int count = api.getPlayerCount();
					BaseComponent[] playersOnline = new ComponentBuilder("").color(ChatColor.YELLOW)
							.append(playerPlural(count) + " currently online.").create();
					if (args.length > 0 && args[0].equals("showall")) {
						Multimap<String, UUID> serverToPlayers = api.getServerToPlayers();
						Multimap<String, String> human = HashMultimap.create();
						for (Map.Entry<String, UUID> entry : serverToPlayers.entries()) {
							human.put(entry.getKey(), uuidTranslator.getNameFromUuid(entry.getValue(), false));
						}
						for (String server : new TreeSet<>(serverToPlayers.keySet())) {
							TextComponent serverName = new TextComponent();
							serverName.setColor(ChatColor.GREEN);
							serverName.setText("[" + server + "] ");
							TextComponent serverCount = new TextComponent();
							serverCount.setColor(ChatColor.YELLOW);
							serverCount.setText("(" + serverToPlayers.get(server).size() + "): ");
							TextComponent serverPlayers = new TextComponent();
							serverPlayers.setColor(ChatColor.WHITE);
							serverPlayers.setText(Joiner.on(", ").join(human.get(server)));
							sender.sendMessage(serverName, serverCount, serverPlayers);
						}
						sender.sendMessage(playersOnline);
					} else {
						sender.sendMessage(playersOnline);
						sender.sendMessage(new ComponentBuilder("To see all players online, use /glist showall.").color(ChatColor.YELLOW).create());
					}
				}
			});
		}
	}

	@Configurable
	public static class FindCommand extends Command {

		@Autowired
		private RedisBungee plugin;

		@Autowired
		private RedisBungeeAPI api;

		@Autowired
		private UUIDTranslator uuidTranslator;

		FindCommand() {
			super("find", "bungeecord.command.find", "rfind");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
				@Override
				public void run() {
					if (args.length > 0) {
						UUID uuid = uuidTranslator.getTranslatedUuid(args[0], true);
						if (uuid == null) {
							sender.sendMessage(PLAYER_NOT_FOUND);
							return;
						}
						ServerInfo si = api.getServerFor(uuid);
						if (si != null) {
							TextComponent message = new TextComponent();
							message.setColor(ChatColor.BLUE);
							message.setText(args[0] + " is on " + si.getName() + ".");
							sender.sendMessage(message);
						} else {
							sender.sendMessage(PLAYER_NOT_FOUND);
						}
					} else {
						sender.sendMessage(NO_PLAYER_SPECIFIED);
					}
				}
			});
		}
	}

	@Configurable
	public static class LastSeenCommand extends Command {

		@Autowired
		private RedisBungee plugin;

		@Autowired
		private RedisBungeeAPI api;

		@Autowired
		private UUIDTranslator uuidTranslator;

		LastSeenCommand() {
			super("lastseen", "redisbungee.command.lastseen", "rlastseen");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
				@Override
				public void run() {
					if (args.length > 0) {
						UUID uuid = uuidTranslator.getTranslatedUuid(args[0], true);
						if (uuid == null) {
							sender.sendMessage(PLAYER_NOT_FOUND);
							return;
						}
						long secs = api.getLastOnline(uuid);
						TextComponent message = new TextComponent();
						if (secs == 0) {
							message.setColor(ChatColor.GREEN);
							message.setText(args[0] + " is currently online.");
						} else if (secs != -1) {
							message.setColor(ChatColor.BLUE);
							message.setText(args[0] + " was last online on " + new SimpleDateFormat().format(secs) + ".");
						} else {
							message.setColor(ChatColor.RED);
							message.setText(args[0] + " has never been online.");
						}
						sender.sendMessage(message);
					} else {
						sender.sendMessage(NO_PLAYER_SPECIFIED);
					}
				}
			});
		}
	}

	@Configurable
	public static class IpCommand extends Command {

		@Autowired
		private RedisBungee plugin;

		@Autowired
		private RedisBungeeAPI api;

		@Autowired
		private UUIDTranslator uuidTranslator;

		IpCommand() {
			super("ip", "redisbungee.command.ip", "playerip", "rip", "rplayerip");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
				@Override
				public void run() {
					if (args.length > 0) {
						UUID uuid = uuidTranslator.getTranslatedUuid(args[0], true);
						if (uuid == null) {
							sender.sendMessage(PLAYER_NOT_FOUND);
							return;
						}
						InetAddress ia = api.getPlayerIp(uuid);
						if (ia != null) {
							TextComponent message = new TextComponent();
							message.setColor(ChatColor.GREEN);
							message.setText(args[0] + " is connected from " + ia.toString() + ".");
							sender.sendMessage(message);
						} else {
							sender.sendMessage(PLAYER_NOT_FOUND);
						}
					} else {
						sender.sendMessage(NO_PLAYER_SPECIFIED);
					}
				}
			});
		}
	}

	@Configurable
	public static class PlayerProxyCommand extends Command {

		@Autowired
		private RedisBungee plugin;

		@Autowired
		private RedisBungeeAPI api;

		@Autowired
		private UUIDTranslator uuidTranslator;

		PlayerProxyCommand() {
			super("pproxy", "redisbungee.command.pproxy");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
				@Override
				public void run() {
					if (args.length > 0) {
						UUID uuid = uuidTranslator.getTranslatedUuid(args[0], true);
						if (uuid == null) {
							sender.sendMessage(PLAYER_NOT_FOUND);
							return;
						}
						String proxy = api.getProxy(uuid);
						if (proxy != null) {
							TextComponent message = new TextComponent();
							message.setColor(ChatColor.GREEN);
							message.setText(args[0] + " is connected to " + proxy + ".");
							sender.sendMessage(message);
						} else {
							sender.sendMessage(PLAYER_NOT_FOUND);
						}
					} else {
						sender.sendMessage(NO_PLAYER_SPECIFIED);
					}
				}
			});
		}
	}

	@Configurable
	public static class SendToAll extends Command {

		@Autowired
		private RedisBungeeAPI api;

		SendToAll() {
			super("sendtoall", "redisbungee.command.sendtoall", "rsendtoall");
		}

		@Override
		public void execute(CommandSender sender, String[] args) {
			if (args.length > 0) {
				String command = Joiner.on(" ").skipNulls().join(args);
				api.sendProxyCommand(command);
				TextComponent message = new TextComponent();
				message.setColor(ChatColor.GREEN);
				message.setText("Sent the command /" + command + " to all proxies.");
				sender.sendMessage(message);
			} else {
				sender.sendMessage(NO_COMMAND_SPECIFIED);
			}
		}
	}

	@Configurable
	public static class ServerId extends Command {

		@Autowired
		private RedisBungeeAPI api;

		ServerId() {
			super("serverid", "redisbungee.command.serverid", "rserverid");
		}

		@Override
		public void execute(CommandSender sender, String[] args) {
			TextComponent textComponent = new TextComponent();
			textComponent.setText("You are on " + api.getServerId() + ".");
			textComponent.setColor(ChatColor.YELLOW);
			sender.sendMessage(textComponent);
		}
	}

	@Configurable
	public static class ServerIds extends Command {

		@Autowired
		private RedisBungeeAPI api;

		public ServerIds() {
			super("serverids", "redisbungee.command.serverids");
		}

		@Override
		public void execute(CommandSender sender, String[] strings) {
			TextComponent textComponent = new TextComponent();
			textComponent.setText("All server IDs: " + Joiner.on(", ").join(api.getAllServers()));
			textComponent.setColor(ChatColor.YELLOW);
			sender.sendMessage(textComponent);
		}
	}

	@Configurable
	public static class PlistCommand extends Command {

		@Autowired
		private RedisBungee plugin;

		@Autowired
		private RedisBungeeAPI api;

		@Autowired
		private UUIDTranslator uuidTranslator;

		@Autowired
		private ServerInformation serverInformation;

		@Autowired
		private ServerManager serverManager;

		PlistCommand() {
			super("plist", "redisbungee.command.plist", "rplist");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
				@Override
				public void run() {
					String proxy = args.length >= 1 ? args[0] : serverInformation.getServerName();
					if (!serverManager.getServerIds().contains(proxy)) {
						sender.sendMessage(new ComponentBuilder(proxy + " is not a valid proxy. See /serverids for valid proxies.").color(ChatColor.RED).create());
						return;
					}
					Set<UUID> players = api.getPlayersOnProxy(proxy);
					BaseComponent[] playersOnline = new ComponentBuilder("").color(ChatColor.YELLOW)
							.append(playerPlural(players.size()) + " currently on proxy " + proxy + ".").create();
					if (args.length >= 2 && args[1].equals("showall")) {
						Multimap<String, UUID> serverToPlayers = api.getServerToPlayers();
						Multimap<String, String> human = HashMultimap.create();
						for (Map.Entry<String, UUID> entry : serverToPlayers.entries()) {
							if (players.contains(entry.getValue())) {
								human.put(entry.getKey(), uuidTranslator.getNameFromUuid(entry.getValue(), false));
							}
						}
						for (String server : new TreeSet<>(human.keySet())) {
							TextComponent serverName = new TextComponent();
							serverName.setColor(ChatColor.RED);
							serverName.setText("[" + server + "] ");
							TextComponent serverCount = new TextComponent();
							serverCount.setColor(ChatColor.YELLOW);
							serverCount.setText("(" + human.get(server).size() + "): ");
							TextComponent serverPlayers = new TextComponent();
							serverPlayers.setColor(ChatColor.WHITE);
							serverPlayers.setText(Joiner.on(", ").join(human.get(server)));
							sender.sendMessage(serverName, serverCount, serverPlayers);
						}
						sender.sendMessage(playersOnline);
					} else {
						sender.sendMessage(playersOnline);
						sender.sendMessage(new ComponentBuilder("To see all players online, use /plist " + proxy + " showall.").color(ChatColor.YELLOW).create());
					}
				}
			});
		}
	}

	public static class DebugCommand extends Command {

		DebugCommand() {
			super("rdebug", "redisbungee.command.debug");
		}

		@Override
		public void execute(final CommandSender sender, final String[] args) {
			/*TextComponent poolActiveStat = new TextComponent("Currently active pool objects: " + plugin.getPool().getNumActive());
			TextComponent poolIdleStat = new TextComponent("Currently idle pool objects: " + plugin.getPool().getNumIdle());
			TextComponent poolWaitingStat = new TextComponent("Waiting on free objects: " + plugin.getPool().getNumWaiters());
			sender.sendMessage(poolActiveStat);
			sender.sendMessage(poolIdleStat);
			sender.sendMessage(poolWaitingStat);*/
			TextComponent textComponent = new TextComponent("THERE IS NO DEBUG INFORMATION AVAILABLE!");
			textComponent.setBold(true);
			textComponent.setColor(ChatColor.RED);
			sender.sendMessage(textComponent);
		}
	}
}
