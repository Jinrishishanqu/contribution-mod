import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {chromium} from 'playwright';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const output=path.join(root,'code/build/documentation/site-previews');
const standalone=path.join(root,'code/build/documentation/standalone-wiki');
await fs.mkdir(output,{recursive:true});
await fs.cp(path.join(root,'wiki'),standalone,{recursive:true});
const pages=(await fs.readdir(standalone)).filter(f=>f.endsWith('.html')).sort();
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
let images=0;
try{
 for(const width of [1280,390]){
  const context=await browser.newContext({viewport:{width,height:950}});const tab=await context.newPage();const errors=[];
  tab.on('pageerror',e=>errors.push(e.message));
  tab.on('requestfailed',request=>errors.push(`request: ${request.url()}`));
  for(const file of pages){
   await tab.goto(pathToFileURL(path.join(standalone,file)).href);
   await tab.evaluate(async()=>{await Promise.all([...document.images].map(img=>{img.loading='eager';return img.decode();}));});
   const result=await tab.evaluate(()=>{
    const problems=[];
    if(document.documentElement.scrollWidth>innerWidth+2)problems.push('horizontal overflow');
    if(document.querySelector('.wiki-page').hidden)problems.push('hidden article');
    if(!document.querySelector('[aria-current="page"]'))problems.push('navigation');
    if(/(?:index|items|recipes|weapon-skins|advancement)/.test(location.pathname) && !document.images.length)problems.push('missing subject illustration');
    for(const img of document.images)if(!img.complete||!img.naturalWidth||!img.alt)problems.push('broken/inaccessible image');
    for(const link of document.querySelectorAll('[src],a[href]')){const url=new URL(link.getAttribute('src')??link.getAttribute('href'),location.href);if(url.protocol==='file:'&&!decodeURI(url.pathname).includes('/standalone-wiki/'))problems.push('asset escapes site');}
    const search=document.getElementById('site-search');search.value='头颅';search.dispatchEvent(new Event('input'));
    if(![...document.querySelectorAll('#site-search-results a')].some(a=>a.textContent==='特殊道具'))problems.push('search');search.value='';search.dispatchEvent(new Event('input'));
    if(innerWidth<1024){document.getElementById('mobile-nav').click();if(!document.body.classList.contains('nav-open'))problems.push('mobile navigation');document.getElementById('mobile-nav').click();}
    const theme=document.getElementById('theme-toggle');theme.click();if(!document.body.classList.contains('dark'))problems.push('theme');theme.click();
    const article=document.querySelector('.article').cloneNode(true);article.querySelectorAll('details,pre,code').forEach(node=>node.remove());
    if(/\b(?:Dialog|dialog|UI|GUI|modifier|tick|EMA|true|false)\b|商城|指令/.test(article.textContent))problems.push('unified terminology');
    return {problems,images:document.images.length};
   });
   if(result.problems.length||errors.length)throw new Error(`${file} ${width}: ${JSON.stringify({result,errors})}`);
   images+=result.images;
   if(['index.html','items.html','recipes-basics.html','recipes-wings.html','recipes-hats.html','weapon-skins.html','special-items.html','advancement-details.html','basics.html'].includes(file))await tab.screenshot({path:path.join(output,`${file.slice(0,-5)}-${width}.png`)});
   if(file==='recipes-basics.html'){await tab.locator('.recipe-figure').first().scrollIntoViewIfNeeded();await tab.screenshot({path:path.join(output,`recipe-detail-${width}.png`)});}
   if(file==='advancement-details.html'){await tab.locator('.wiki-section').nth(1).scrollIntoViewIfNeeded();await tab.screenshot({path:path.join(output,`advancement-detail-${width}.png`)});}
   if(file==='weapon-skins.html'){await tab.locator('.wiki-section').nth(1).scrollIntoViewIfNeeded();await tab.screenshot({path:path.join(output,`skin-gallery-${width}.png`)});}
  }
  await context.close();
 }
 const noScript=await browser.newContext({viewport:{width:390,height:950},javaScriptEnabled:false});const tab=await noScript.newPage();
 await tab.goto(pathToFileURL(path.join(standalone,'index.html')).href);
 if(!await tab.locator('.wiki-page').isVisible()||!await tab.locator('.sidebar').isVisible())throw new Error('no-script navigation');
 await noScript.close();
 const legacy=await browser.newPage();
 await legacy.goto(pathToFileURL(path.join(root,'documentation/wiki.html')).href+'#recipes-basics');
 await legacy.waitForSelector('#recipes-basics:not([hidden])');
 await legacy.evaluate(async()=>{await Promise.all([...document.querySelectorAll('#recipes-basics img')].map(img=>{img.loading='eager';return img.decode();}));});
 await legacy.close();
 if(!(await fs.stat(path.join(standalone,'commands.xlsx'))).size)throw new Error('missing workbook');
 console.log(`WIKI_BROWSER_PASS: ${pages.length} pages × 2 viewports, ${images} loaded images, standalone assets, search, navigation, theme, no-script and workbook`);
}finally{await browser.close();}
