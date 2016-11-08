package com.imaginarycode.minecraft.redisbungee.util.uuid;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NameFetcher {

	private RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());

    public List<String> nameHistoryFromUuid(UUID uuid) throws RestClientException {
		NameList res = restTemplate.getForObject(
			"https://api.mojang.com/user/profiles/{uuid}/names",
			NameList.class,
			uuid.toString().replace("-", ""));

		List<String> humanNames = new ArrayList<>();
        for (Name name : res.getNames()) {
            humanNames.add(name.name);
        }
        return humanNames;
    }

    public static class NameList {
    	@Getter
    	private List<Name> names;
    }

    public static class Name {
        private String name;
        private long changedToAt;
    }
}