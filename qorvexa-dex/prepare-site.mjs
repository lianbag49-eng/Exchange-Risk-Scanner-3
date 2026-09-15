import {mkdir,copyFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {dirname,join} from 'node:path';
const source=dirname(fileURLToPath(import.meta.url)),root=process.argv.includes('--root'),out=root?'public':'public/beltrix';
await mkdir(out,{recursive:true});
for(const name of ['index.html','app.js','market.js','terminal-core.js','trading.bundle.js','wallet.bundle.js','wallet.css','terminal.css','sw.js','icon.svg','beltrix-symbol.svg','beltrix-icon.png','manifest.webmanifest'])await copyFile(join(source,name),join(out,name));
if(!root){await mkdir('public/qorvexa-dex',{recursive:true});await writeFile('public/qorvexa-dex/index.html','<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BELTRIX</title><body style="background:#08090b;color:#e6be72;font:18px system-ui;padding:40px"><p>BELTRIX has a new address.</p><a style="color:inherit" href="../beltrix/">Open BELTRIX</a><script>location.replace("../beltrix/"+location.search+location.hash)</script></body></html>');}
