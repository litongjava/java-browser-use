() => {
  const links = Array.from(document.querySelectorAll('a'));
  const loginLinks = links.filter(a => /\/login/.test(a.getAttribute('href') || '')).map(a => a.textContent.trim());
  const header = document.querySelector('.navbar, .site-header, #git-header-nav, header');
  return {
    url: location.href,
    title: document.title,
    loginLinks: loginLinks,
    headerText: (header ? header.innerText : document.body.innerText).slice(0, 400)
  };
}
