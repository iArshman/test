export default {
  input: "./build/typescript/main.js",
  output: {
    file: "./build/loader.js",
    format: "iife",
  },
  onwarn(warning, warn) {
    // Eval is intentional for the Valdi console; silence Rollup's advisory.
    if (warning.code === "EVAL") return;
    warn(warning);
  }
};
