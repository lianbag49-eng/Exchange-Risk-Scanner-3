// Export only BELTRIX source and its own build/tests. ERS is not copied.
import {mkdir,readdir,readFile,writeFile,copyFile,stat} from 'node:fs/promises';
import {resolve,join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..'),target=resolve(process.argv[2]||join(root,'beltrix-standalone'));
if(target===root||target.startsWith(join(root,'qorvexa-dex')))throw Error('Choose a separate destination');
try{await stat(target);throw Error('Destination exists; choose a new empty path')}catch(e){if(e.code!=='ENOENT')throw e}
await mkdir(join(target,'web'),{recursive:true});
async function copy(source,dest){for(const entry of await readdir(source,{withFileTypes:true})){if(entry.name.startsWith('.')||entry.name==='node_modules'||entry.name==='test-results'||entry.name.endsWith('.bundle.js'))continue;const from=join(source,entry.name),to=join(dest,entry.name);if(entry.isDirectory()){if(entry.name==='tests'){await mkdir(to);await copy(from,to)}continue}if(/\.(js|mjs|cjs|css|html|svg|webmanifest|md)$/.test(entry.name)){const text=await readFile(from,'utf8');await writeFile(to,text.replaceAll('qorvexa-dex','web'))}else if(/\.(png|jpg)$/.test(entry.name))await copyFile(from,to)}}
await copy(join(root,'qorvexa-dex'),join(target,'web'));
const pkg=JSON.parse(await readFile(join(root,'package.json'),'utf8'));pkg.name='beltrix';for(const k of Object.keys(pkg.scripts))pkg.scripts[k]=pkg.scripts[k].replaceAll('qorvexa-dex','web');await writeFile(join(target,'package.json'),JSON.stringify(pkg,null,2)+'\n');
const lock=JSON.parse(await readFile(join(root,'package-lock.json'),'utf8'));lock.name='beltrix';if(lock.packages?.[''])lock.packages[''].name='beltrix';await writeFile(join(target,'package-lock.json'),JSON.stringify(lock,null,2)+'\n');
await mkdir(join(target,'.github/workflows'),{recursive:true});let workflow=await readFile(join(root,'.github/workflows/deploy-qorvexa.yml'),'utf8');workflow=workflow.replaceAll('qorvexa-dex','web').replaceAll('qorvexa-test-results','beltrix-test-results').replaceAll('deploy-qorvexa.yml','deploy.yml').replaceAll('qorvexa-pages','beltrix-pages').replace("run: node web/prepare-site.mjs","run: node web/prepare-site.mjs --root").replace("page_url }}beltrix/","page_url }}");await writeFile(join(target,'.github/workflows/deploy.yml'),workflow);
await writeFile(join(target,'.gitignore'),'node_modules/\npublic/\ntest-results/\nplaywright-report/\nweb/*.bundle.js\n');
await writeFile(join(target,'README.md'),'# BELTRIX\n\nIndependent wallet and Hyperliquid trading frontend. ERS Android/iOS code is not included.\n\nInstall with `npm ci`, build with `npm run build`, and test with `npm run test:unit` and `npm test`. Set GitHub Pages source to GitHub Actions. The workflow deploys the site at the repository root URL, e.g. `https://OWNER.github.io/beltrix/`.\n\nMainnet actions use real account funds and always require wallet signature and explicit review. Tests use invented balances and intercepted exchange requests; no real order is sent by CI. See web/TERMINAL-RELEASE.md.\n');
console.log('BELTRIX-only source exported to '+target);
