import { readFile } from "node:fs/promises";
import { ReservationEngine } from "./engine.js";

const [trainPath = "data/train.json", commandPath = "data/commands.json"] = process.argv.slice(2);
try {
  const train = JSON.parse(await readFile(trainPath, "utf8"));
  const commands = JSON.parse(await readFile(commandPath, "utf8"));
  if (!Array.isArray(commands)) throw new Error("Command file must contain a JSON array");
  const engine = new ReservationEngine(train);
  for (const command of commands) {
    try { console.log(JSON.stringify(engine.execute(command))); }
    catch (error) { console.log(JSON.stringify({ type: "error", command: command.type, id: command.id ?? command.bookingId ?? null, message: error.message })); }
  }
} catch (error) {
  console.error(`Unable to run reservation commands: ${error.message}`);
  process.exitCode = 1;
}
