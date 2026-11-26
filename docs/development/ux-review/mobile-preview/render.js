const { chromium } = require("playwright");
const path = require("path");
const fs = require("fs");

const directory = __dirname;
const indexUrl = "file://" + path.join(directory, "index.html");
const states = [
  ["01-video-paused", "paused"],
  ["02-video-playing", "playing"],
  ["03-document", "document"],
  ["04-spoiler-blur", "blur"],
  ["05-video-dark", "dark"],
  ["06-folder", "folder"],
  ["07-image", "image"],
  ["08-audio", "audio"],
  ["09-archive", "archive"],
  ["10-generic", "generic"],
];

(async () => {
  const executablePath = [
    process.env.PIKO_CHROMIUM,
    "/mnt/kangjin-default/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome",
    "/mnt/kangjin-default/.cache/ms-playwright/chromium-1148/chrome-linux/chrome",
  ].find((candidate) => candidate && fs.existsSync(candidate));
  const browser = await chromium.launch({
    executablePath,
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });
  const context = await browser.newContext({
    viewport: { width: 412, height: 892 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();

  for (const [name, state] of states) {
    await page.goto(`${indexUrl}?state=${state}`);
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(250);
    await page.screenshot({
      path: path.join(directory, `${name}.png`),
      animations: "disabled",
    });
    console.log(`rendered ${name}.png`);
  }

  const overview = await browser.newPage({
    viewport: { width: 1960, height: 1060 },
    deviceScaleFactor: 1,
  });
  await overview.goto("file://" + path.join(directory, "overview.html"));
  await overview.evaluate(() => document.fonts.ready);
  await overview.waitForTimeout(300);
  await overview.screenshot({
    path: path.join(directory, "contact-sheet.png"),
    fullPage: true,
    animations: "disabled",
  });
  console.log("rendered contact-sheet.png");

  const types = await browser.newPage({
    viewport: { width: 1960, height: 1060 },
    deviceScaleFactor: 1,
  });
  await types.goto("file://" + path.join(directory, "types.html"));
  await types.evaluate(() => document.fonts.ready);
  await types.waitForTimeout(300);
  await types.screenshot({
    path: path.join(directory, "types-sheet.png"),
    fullPage: true,
    animations: "disabled",
  });
  console.log("rendered types-sheet.png");

  await browser.close();
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
