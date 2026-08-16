/**
 * Bundle Monaco locally instead of fetching it from a CDN.
 *
 * `@monaco-editor/react` defaults to loading the editor from jsdelivr at
 * runtime. That would make the app unusable in exactly the deployment the
 * architecture is built for — an air-gapped on-premise install with no route to
 * the internet — and it would leak an outbound request from a machine whose
 * whole point is not making any.
 *
 * Importing `editor.api` rather than the package root deliberately leaves out
 * the TypeScript, CSS, HTML and JSON *language services*, which together weigh
 * about 9 MB of web workers and serve none of our targets. The basic-languages
 * contribution still provides tokenising for Python, C# and Scheme, each
 * code-split and fetched only when a script of that kind is opened.
 *
 * Note: the package's exports map rewrites "./*" to "./esm/vs/*.js", so every
 * subpath below is written without the esm/vs prefix.
 */

import * as monaco from "monaco-editor/editor/editor.api";
import "monaco-editor/basic-languages/monaco.contribution";
import editorWorker from "monaco-editor/editor/editor.worker?worker";
import { loader } from "@monaco-editor/react";

self.MonacoEnvironment = {
  getWorker: () => new editorWorker(),
};

loader.config({ monaco });
