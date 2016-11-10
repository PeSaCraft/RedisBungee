package com.imaginarycode.minecraft.redisbungee.pubsub;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.stereotype.Component;

import de.pesacraft.bungee.core.server.ServerInformation;
import de.pesacraft.shares.config.MessageListenerConfiguration;
import lombok.RequiredArgsConstructor;

@Configuration
class AllRedisBungeeReceivers {

	@Bean
	RedisBungeeReceiverConfiguration redisBungeeSpecificChannelReceiver(@Autowired ServerInformation serverInformation) {
		return new RedisBungeeReceiverConfiguration("redisbungee-" + serverInformation.getServerName());
	}

	@Bean
	RedisBungeeReceiverConfiguration redisBungeeGeneralChannelAReceiver() {
		return new RedisBungeeReceiverConfiguration("redisbungee-allservers");
	}

	@Bean
	RedisBungeeReceiverConfiguration redisBungeeDataChannelAReceiver() {
		return new RedisBungeeReceiverConfiguration("redisbungee-data");
	}
}

@Configurable
@RequiredArgsConstructor
public class RedisBungeeReceiverConfiguration implements MessageListenerConfiguration {

	@Autowired
	private RedisBungeReceiver commandReceiver;

	@Autowired
	private GenericJackson2JsonRedisSerializer jsonRedisSerializer;

	private final String channelName;

	@Override
	public MessageListener getMessageListener() {
		MessageListenerAdapter listenerAdapter = new MessageListenerAdapter(commandReceiver, "receiveMessage");
		listenerAdapter.setSerializer(jsonRedisSerializer);
		listenerAdapter.afterPropertiesSet();
		return listenerAdapter;
	}

	@Override
	public Topic getTopic() {
		return new ChannelTopic(channelName);
	}

}
