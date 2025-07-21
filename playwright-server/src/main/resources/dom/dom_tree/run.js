(args)=>{
  const buildDomTextExp = %s
  let result = buildDomTextExp(args);
  
  // 下面这段 JS 抓当前滚动 & 页面尺寸
  const scrollY        = window.scrollY;
  const viewportH      = window.innerHeight;
  const pageH          = document.documentElement.scrollHeight;
  const pixels_above   = scrollY;
  const pixels_below   = pageH - scrollY - viewportH;

  // 把滚动信息塞到返回值里
  result.pixels_above = pixels_above;
  result.pixels_below = pixels_below;
  result.viewport_height = viewportH;
  result.page_height     = pageH;  
  
  return result; 
}