import {build} from 'esbuild';
for(const entry of ['trading','wallet'])await build({entryPoints:[`qorvexa-dex/${entry}.js`],outfile:`qorvexa-dex/${entry}.bundle.js`,bundle:true,format:'esm',target:'es2022',minify:true,legalComments:'eof'});
