package com.imaginarycode.minecraft.redisbungee.pubsub;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;

import net.md_5.bungee.api.ProxyServer;

@Component
public class RedisBungeReceiver {

	@Autowired
	private RedisBungee plugin;

	public void receiveMessage(String message, String channel) {
		if (message.trim().length() == 0) return;

		ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
			@Override
			public void run() {
				ProxyServer.getInstance().getPluginManager().callEvent(new PubSubMessageEvent(channel, message));
			}
		});
	}
}
