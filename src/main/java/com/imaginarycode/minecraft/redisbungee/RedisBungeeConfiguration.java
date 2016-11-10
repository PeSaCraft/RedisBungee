package com.imaginarycode.minecraft.redisbungee;

import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import lombok.Getter;

import java.net.InetAddress;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisBungeeConfiguration {

	@Getter
	@Value("${redisbungee.registerBungeeCommands}")
	private boolean registerBungeeCommands;

	@Value("${redisbungee.exemptAddresses}")
	private List<String> exemptAddresses;

	public List<InetAddress> getExemptAddresses() {
		ImmutableList.Builder<InetAddress> addressBuilder = ImmutableList.builder();

		for (String s : exemptAddresses) {
			addressBuilder.add(InetAddresses.forString(s));
		}

		return addressBuilder.build();
	}
}
