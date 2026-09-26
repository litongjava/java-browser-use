() => {
  const links = Array.from(document.querySelectorAll('a')).map(a => ({
    href: a.getAttribute('href') || '',
    text: a.textContent.trim().slice(0, 30)
  })).filter(x => x.href && !/^https?:/.test(x.href));
  const avatar = document.querySelector('img[class*=avatar], .avatar img');
  return {
    url: location.href,
    title: document.title,
    avatar: avatar ? avatar.src : null,
    mine: links.filter(x => /notifications|dashboard|projects/.test(x.href)).slice(0, 20),
    bodyHead: document.body.innerText.slice(0, 300)
  };
}
