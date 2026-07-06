import type { Plugin } from "@opencode-ai/plugin";

const plugin: Plugin = async () => {
  return {
    "tool.definition": async (_input, output) => {
      const extOutput = output as Record<string, unknown>;
      const schema = extOutput.jsonSchema as Record<string, unknown> | undefined;

      if (!schema || typeof schema !== "object") return;
      if (schema.type !== "object") return;
      if (schema.required !== undefined) return;

      extOutput.jsonSchema = { ...schema, required: [] };
    },
  };
};

export default plugin;
