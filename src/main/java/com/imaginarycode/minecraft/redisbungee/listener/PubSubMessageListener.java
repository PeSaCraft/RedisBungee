package com.imaginarycode.minecraft.redisbungee.listener;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCommandSender;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCore;
import com.imaginarycode.minecraft.redisbungee.events.PlayerChangedServerNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.events.PlayerJoinedNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.events.PlayerLeftNetworkEvent;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager.DataManagerMessage;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager.LoginPayload;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager.LogoutPayload;
import com.imaginarycode.minecraft.redisbungee.manager.CachedDataManager.ServerChangePayload;

import de.pesacraft.bungee.core.server.ServerInformation;
import net.md_5.bungee.api.plugin.Event;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

@Component
public class PubSubMessageListener implements Listener, InitializingBean {

	@Autowired
	private RedisBungee plugin;

	@Autowired
	private CachedDataManager cachedDataManager;

	@Autowired
	private ServerInformation serverInformation;

	@Autowired
	private RedisBungeeCommandSender redisBungeeCommandSender;

	private final JsonParser parser = new JsonParser();

	@Override
	public void afterPropertiesSet() throws Exception {
		plugin.getProxy().getPluginManager().registerListener(plugin, this);
	}

	@EventHandler
    public void onPubSubMessageCommands(PubSubMessageEvent event) {
        if (event.getChannel().equals("redisbungee-allservers") || event.getChannel().equals("redisbungee-" + serverInformation.getServerName())) {
            String message = event.getMessage();
            if (message.startsWith("/"))
                message = message.substring(1);
            plugin.getLogger().info("Invoking command via PubSub: /" + message);
            plugin.getProxy().getPluginManager().dispatchCommand(redisBungeeCommandSender, message);
        }
    }

	@EventHandler
	public void onPubSubMessage(PubSubMessageEvent event) {
		if (!event.getChannel().equals("redisbungee-data"))
			return;

		// Partially deserialize the message so we can look at the action
		JsonObject jsonObject = parser.parse(event.getMessage()).getAsJsonObject();

		String source = jsonObject.get("source").getAsString();

		if (source.equals(serverInformation.getServerName()))
			return;

		DataManagerMessage.Action action = DataManagerMessage.Action.valueOf(jsonObject.get("action").getAsString());

		switch (action) {
			case JOIN:
				final DataManagerMessage<LoginPayload> message1 = RedisBungeeCore.getGson().fromJson(jsonObject, new TypeToken<DataManagerMessage<LoginPayload>>(){}.getType());

				cachedDataManager.playerJoined(message1.getTarget(), message1.getSource(), message1.getPayload().getAddress());
				callEvent(new PlayerJoinedNetworkEvent(message1.getTarget()));
				break;
			case LEAVE:
				final DataManagerMessage<LogoutPayload> message2 = RedisBungeeCore.getGson().fromJson(jsonObject, new TypeToken<DataManagerMessage<LogoutPayload>>(){}.getType());

				cachedDataManager.playerLeft(message2.getTarget(), message2.getPayload().getTimestamp());
				callEvent(new PlayerLeftNetworkEvent(message2.getTarget()));
				break;
			case SERVER_CHANGE:
				final DataManagerMessage<ServerChangePayload> message3 = RedisBungeeCore.getGson().fromJson(jsonObject, new TypeToken<DataManagerMessage<ServerChangePayload>>(){}.getType());

				cachedDataManager.playerSwitchedServer(message3.getTarget(), message3.getPayload().getServer());
				callEvent(new PlayerChangedServerNetworkEvent(message3.getTarget(), message3.getPayload().getOldServer(), message3.getPayload().getServer()));
				break;
		}
	}

	private void callEvent(Event event) {
		plugin.getProxy().getScheduler().runAsync(plugin, new Runnable() {
			@Override
			public void run() {
				plugin.getProxy().getPluginManager().callEvent(event);
			}
		});
	}
}
