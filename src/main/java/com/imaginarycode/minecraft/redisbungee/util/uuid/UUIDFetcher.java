package com.imaginarycode.minecraft.redisbungee.util.uuid;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeCore;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/* Credits to evilmidget38 for this class. I modified it to use Gson. */
@Component
public class UUIDFetcher {
	private static final double PROFILES_PER_REQUEST = 100;
	private static final String PROFILE_URL = "https://api.mojang.com/profiles/minecraft";
	private static final MediaType JSON = MediaType.parse("application/json");

	private final RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());

	@Autowired
	private UUIDTranslator uuidTranslator;

	@Autowired
	private Gson gson;

	public Map<String, UUID> getUUIDs(List<String> names, boolean rateLimiting) {
		List<HttpMessageConverter<?>> messageConverters = new ArrayList<>();
		messageConverters.add(new FormHttpMessageConverter());
		messageConverters.add(new StringHttpMessageConverter());
		messageConverters.add(new MappingJackson2HttpMessageConverter());
		restTemplate.setMessageConverters(messageConverters);

		//String url = "http://10.1.1.40:9998/event/1";
		//Event event = restTemplate.getForObject(url, Event.class, urlVariables);

/*		try {
			JSONObject jsonObject = new JSONObject(event);

			Log.d(TAG, "Result: [" + jsonObject.get("id") + "]");
			Log.d(TAG, "Result: [" + jsonObject.get("title") + "]");
			Log.d(TAG, "Result: [" + jsonObject.get("Locations") + "]");
		} catch (JSONException ex) {
			ex.printStackTrace();
		}
*/
		Map<String, UUID> uuidMap = new HashMap<>();
		int requests = (int) Math.ceil(names.size() / PROFILES_PER_REQUEST);
		for (int i = 0; i < requests; i++) {
			String body = gson.toJson(names.subList(i * 100, Math.min((i + 1) * 100, names.size())));
			Profile[] profiles = restTemplate.postForObject(PROFILE_URL, body, Profile[].class);

			for (Profile profile : profiles) {
				UUID uuid = uuidTranslator.getMojangianUUID(profile.id);
				uuidMap.put(profile.name, uuid);
			}
			if (rateLimiting && i != requests - 1) {
				try {
					Thread.sleep(100L);
				} catch (InterruptedException ignored) {}
			}
		}
		return uuidMap;
	}

	private static class Profile {
		String id;
		String name;
	}
}
