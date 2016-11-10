package com.imaginarycode.minecraft.redisbungee.manager;

import com.imaginarycode.minecraft.redisbungee.pubsub.RedisBungeeReceiverConfiguration;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * This class manages all the data that RedisBungee fetches from Redis, along with updates to that data.
 *
 * @since 0.3.3
 */
@Component
public class MessageListenerManager {

	@Autowired
	private RedisMessageListenerContainer redisMessageListenerContainer;

	private Map<String, MessageListener> registeredListeners = new HashMap<>();

	public void registerChannels(String... channels) {
		for (String channel : channels) {
			if (registeredListeners.containsKey(channel))
				throw new IllegalArgumentException("Tried to register channel " + channel + " which is already registered!");

			RedisBungeeReceiverConfiguration config = new RedisBungeeReceiverConfiguration(channel);
			registeredListeners.put(channel, config.getMessageListener());
			redisMessageListenerContainer.addMessageListener(config.getMessageListener(), config.getTopic());
		}
	}

	public void unregisterChannels(String... channels) {
		for (String channel : channels) {
			if (!registeredListeners.containsKey(channel))
				throw new IllegalArgumentException("Tried to unregister channel " + channel + " which isn't registered!");

			MessageListener messageListener = registeredListeners.remove(channel);
			redisMessageListenerContainer.removeMessageListener(messageListener);
		}
	}
}
