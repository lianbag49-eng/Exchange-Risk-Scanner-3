import { build } from 'esbuild';
import { mkdir,copyFile,readFile,writeFile } from 'node:fs/promises';
await mkdir('dist',{recursive:true});
await build({entryPoints:['entry.js'],bundle:true,format:'esm',target:['safari16','chrome110'],outfile:'dist/app.js',minify:true,legalComments:'linked'});
for(const f of ['index.html','terminal.css','icon.svg','manifest.webmanifest','sw.js'])await copyFile(f,'dist/'+f);
const notices=await Promise.all(['@nktkas/hyperliquid','lightweight-charts','viem'].map(async n=>n+'\n'+await readFile('node_modules/'+n+'/LICENSE','utf8')));
await writeFile('dist/THIRD-PARTY-NOTICES.txt',notices.join('\n\n'));
