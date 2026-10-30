browser.runtime.onMessage.addListener((message) => {
  browser.runtime.sendNativeMessage("dejavuMouse", message);
});
