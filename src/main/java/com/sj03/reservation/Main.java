package com.sj03.reservation;

import com.sj03.reservation.engine.ReservationEngine;
import com.sj03.reservation.json.JsonParser;
import com.sj03.reservation.json.JsonWriter;
import com.sj03.reservation.model.TrainDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-file CLI entrypoint for the PS-003 railway reservation engine.
 */
public final class Main {
  private Main() {}

  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    String trainPath = args.length >= 1 ? args[0] : "data/train.json";
    String commandPath = args.length >= 2 ? args[1] : "data/commands.json";
    try {
      String trainJson = Files.readString(Path.of(trainPath), StandardCharsets.UTF_8);
      String commandsJson = Files.readString(Path.of(commandPath), StandardCharsets.UTF_8);
      TrainDefinition train = TrainDefinition.fromMap(JsonParser.parseObject(trainJson));
      List<Object> commands = JsonParser.parseArray(commandsJson);
      ReservationEngine engine = new ReservationEngine(train);

      for (Object item : commands) {
        if (!(item instanceof Map<?, ?> rawCmd)) {
          Map<String, Object> err = new LinkedHashMap<>();
          err.put("type", "error");
          err.put("command", null);
          err.put("id", null);
          err.put("message", "Command entry must be a JSON object");
          System.out.println(JsonWriter.stringify(err));
          continue;
        }
        Map<String, Object> command = (Map<String, Object>) rawCmd;
        try {
          Map<String, Object> event = engine.execute(command);
          System.out.println(JsonWriter.stringify(event));
        } catch (Exception e) {
          Map<String, Object> err = new LinkedHashMap<>();
          err.put("type", "error");
          err.put("command", command.get("type"));
          Object id = command.get("id") != null ? command.get("id") : command.get("bookingId");
          err.put("id", id);
          err.put("message", e.getMessage());
          System.out.println(JsonWriter.stringify(err));
        }
      }
    } catch (Exception e) {
      System.err.println("Unable to run reservation commands: " + e.getMessage());
      System.exit(1);
    }
  }
}
