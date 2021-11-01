package com.linkedin.venice.controllerapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.venice.exceptions.VeniceException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.linkedin.venice.controllerapi.ControllerApiConstants.*;


public class UpdateClusterConfigQueryParams extends QueryParams {
  public UpdateClusterConfigQueryParams(Map<String, String> initialParams) {
    super(initialParams);
  }

  public UpdateClusterConfigQueryParams() {
    super();
  }

  private ObjectMapper mapper = new ObjectMapper();

  public UpdateClusterConfigQueryParams setServerKafkaFetchQuotaRecordsPerSecondForRegion(String region, long kafkaFetchQuotaRecordsPerSecond) {
    Map<String, String> serverKafkaFetchQuotaRecordsPerSecond = getStringMap(SERVER_KAFKA_FETCH_QUOTA_RECORDS_PER_SECOND).orElse(new HashMap<>());
    serverKafkaFetchQuotaRecordsPerSecond.put(region, String.valueOf(kafkaFetchQuotaRecordsPerSecond));
    return putStringMap(SERVER_KAFKA_FETCH_QUOTA_RECORDS_PER_SECOND, serverKafkaFetchQuotaRecordsPerSecond);
  }

  public Optional<Map<String, Integer>> getServerKafkaFetchQuotaRecordsPerSecond() {
    return getStringMap(SERVER_KAFKA_FETCH_QUOTA_RECORDS_PER_SECOND).map(serverKafkaFetchQuotaRecordsPerSecondStr -> {
      Map<String, Integer> serverKafkaFetchQuotaRecordsPerSecond = new HashMap<>();
      for (Map.Entry<String, String> regionToQuota: serverKafkaFetchQuotaRecordsPerSecondStr.entrySet()) {
        serverKafkaFetchQuotaRecordsPerSecond.put(regionToQuota.getKey(), Integer.parseInt(regionToQuota.getValue()));
      }

      return serverKafkaFetchQuotaRecordsPerSecond;
    });
  }

  public UpdateClusterConfigQueryParams setStoreMigrationAllowed(boolean storeMigrationAllowed) {
    return putBoolean(ALLOW_STORE_MIGRATION, storeMigrationAllowed);
  }

  public Optional<Boolean> getStoreMigrationAllowed() {
    return getBoolean(ALLOW_STORE_MIGRATION);
  }

  //***************** above this line are getters and setters *****************

  private UpdateClusterConfigQueryParams putBoolean(String name, boolean value) {
    return (UpdateClusterConfigQueryParams) add(name, value);
  }

  private Optional<Boolean> getBoolean(String name) {
    return Optional.ofNullable(params.get(name)).map(Boolean::valueOf);
  }

  private UpdateClusterConfigQueryParams putStringMap(String name, Map<String, String> value) {
    try {
      return (UpdateClusterConfigQueryParams) add(
          name,
          mapper.writeValueAsString(value)
      );
    } catch (JsonProcessingException e) {
      throw new VeniceException(e.getMessage());
    }
  }

  private Optional<Map<String, String>> getStringMap(String name) {
    if (!params.containsKey(name)) {
      return Optional.empty();
    } else {
      try {
        return Optional.of(mapper.readValue(params.get(name), Map.class));
      } catch (IOException e) {
        throw new VeniceException(e.getMessage());
      }
    }
  }
}