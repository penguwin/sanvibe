#!/usr/bin/env node
import { addClassPath, loadFile } from 'nbb';
import { fileURLToPath } from 'node:url';

// Loading the file itself (rather than a string) makes nbb resolve npm modules
// from the project instead of the current directory.
const src = fileURLToPath(new URL('../src', import.meta.url));
addClassPath(src);
const { main } = await loadFile(`${src}/sanvibe/cli.cljs`);
main(...process.argv.slice(2));
