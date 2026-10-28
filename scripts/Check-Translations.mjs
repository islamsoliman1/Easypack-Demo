import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'..'),src=path.join(root,'frontend/src');
const ui=Object.assign({},...['translations.json','notifications/translations.json','factory-translations.json','catalog-media-translations.json'].map(name=>JSON.parse(fs.readFileSync(path.join(src,name),'utf8'))));
const content=JSON.parse(fs.readFileSync(path.join(src,'demo-content.json'),'utf8'));
const system=JSON.parse(fs.readFileSync(path.join(src,'system-content.json'),'utf8'));
const notifications=JSON.parse(fs.readFileSync(path.join(src,'notification-content.json'),'utf8'));
assert.deepEqual(content,JSON.parse(fs.readFileSync(path.join(root,'backendjava/src/main/resources/localization/demo-content.json'),'utf8')),'Frontend/backend reviewed content must match');
for(const name of ['system-content','notification-content'])assert.deepEqual(JSON.parse(fs.readFileSync(path.join(src,name+'.json'),'utf8')),JSON.parse(fs.readFileSync(path.join(root,'backendjava/src/main/resources/localization',name+'.json'),'utf8')),name+' parity');
for(const [key,value] of Object.entries({...ui,...content,...notifications})) {
 assert.ok(value.trim(),`Empty translation: ${key}`);
 assert.ok(!/[\u0600-\u06ff]/.test(value),`Untranslated English value: ${key}`);
 assert.deepEqual([...key.matchAll(/\{\{([^}]+)\}\}/g)].map(m=>m[1]).sort(),[...value.matchAll(/\{\{([^}]+)\}\}/g)].map(m=>m[1]).sort(),`Interpolation parity: ${key}`);
}
function sourceFiles(dir, prefix='') { return fs.readdirSync(dir,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?sourceFiles(path.join(dir,entry.name),prefix+entry.name+'/'):/\.tsx?$/.test(entry.name)?[prefix+entry.name]:[]); }
for(const name of sourceFiles(src)) {
 const source=fs.readFileSync(path.join(src,name),'utf8');
 for(const match of source.matchAll(/translateUi\(\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')/g)) {
  const literal=match[1];
  const key=literal[0]==='"'?JSON.parse(literal):JSON.parse('"'+literal.slice(1,-1).replace(/\\'/g,"'").replace(/"/g,'\\"')+'"');
  assert.ok(Object.hasOwn(ui,key)||Object.hasOwn(content,key),`Missing key in ${name}: ${key}`);
 }
 if(!['localizedQuery.ts'].includes(name)) assert.ok(!/import \{[^}]*\buseQuery\b[^}]*\} from '@tanstack\/react-query'/.test(source),`Unlocalized query in ${name}`);
 // Cover strings used through status/permission maps and validation schemas too,
 // rather than checking only direct translateUi calls.
 if (!['LanguageToggle.tsx','i18n.ts'].includes(name)) for (const match of source.matchAll(/"([^"\\\r\n]*(?:\\.[^"\\\r\n]*)*)"|'([^'\\\r\n]*(?:\\.[^'\\\r\n]*)*)'/g)) {
  const value=match[1] ?? match[2];
  if (/[\u0621-\u064a]/.test(value)) assert.ok(Object.hasOwn(ui,value)||Object.hasOwn(content,value)||Object.hasOwn(notifications,value)||Object.values(system).includes(value),`Uncatalogued Arabic string in ${name}: ${value}`);
 }
}
const javaRoot=path.join(root,'backendjava/src/main/java');
const errorKeys=new Set(fs.readFileSync(path.join(root,'backendjava/src/main/resources/localization/messages_en.properties'),'utf8').split(/\r?\n/).map(line=>line.split('=')[0]));
function scan(dir) { for(const item of fs.readdirSync(dir,{withFileTypes:true})) { const filename=path.join(dir,item.name);if(item.isDirectory())scan(filename);else if(filename.endsWith('.java')) {const source=fs.readFileSync(filename,'utf8');for(const match of source.matchAll(/new ApiException\(\s*\d+\s*,\s*"([A-Z_]+)"/g))assert.ok(errorKeys.has(match[1]),`Missing API error translation ${match[1]}`);}} }
scan(javaRoot);
const arabicErrorKeys=new Set(fs.readFileSync(path.join(root,'backendjava/src/main/resources/localization/messages_ar.properties'),'utf8').split(/\r?\n/).filter(line=>line.includes('=')).map(line=>line.split('=')[0]));
assert.deepEqual([...errorKeys].filter(Boolean).sort(),[...arabicErrorKeys].sort(),'Arabic/English API error key parity');
assert.ok(fs.readFileSync(path.join(src,'api.ts'),'utf8').includes('/auth/demo'),'Demo authentication must retain passwordless visitor entry');
console.log(`Verified ${Object.keys(ui).length} UI translations, ${Object.keys(content).length} reviewed content translations, interpolation parity, API error coverage and locale query imports.`);
