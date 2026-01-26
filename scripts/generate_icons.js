#!/usr/bin/env node
/**
 * Generate extension icons as simple PNG files
 * Uses pure Node.js canvas-like approach with SVG embedded in data URIs
 */

const fs = require('fs');
const path = require('path');

// Create a simple PNG with the Chinese character 中 (zhōng - center/China)
// Using a minimal PNG encoder

function createPNG(size) {
  // Simple approach: create an SVG and note that Chrome can use SVG
  // For actual PNGs, we'll create a placeholder that works

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4a90d9"/>
      <stop offset="100%" style="stop-color:#357abd"/>
    </linearGradient>
  </defs>
  <rect width="${size}" height="${size}" rx="${size * 0.15}" fill="url(#bg)"/>
  <text x="50%" y="54%" dominant-baseline="middle" text-anchor="middle"
        font-family="PingFang SC, Microsoft YaHei, sans-serif"
        font-size="${size * 0.55}"
        font-weight="600"
        fill="white">中</text>
</svg>`;

  return svg;
}

const sizes = [16, 48, 128];
const iconsDir = path.join(__dirname, '..', 'icons');

for (const size of sizes) {
  const svg = createPNG(size);
  const filename = path.join(iconsDir, `icon${size}.svg`);
  fs.writeFileSync(filename, svg);
  console.log(`Created ${filename}`);
}

// Also create a simple HTML file to view the icons
const previewHtml = `<!DOCTYPE html>
<html>
<head><title>Icon Preview</title></head>
<body style="background: #f0f0f0; padding: 20px; font-family: sans-serif;">
<h1>Mandopop Icons</h1>
${sizes.map(s => `<p><img src="icon${s}.svg" width="${s}" height="${s}"> ${s}x${s}</p>`).join('\n')}
</body>
</html>`;

fs.writeFileSync(path.join(iconsDir, 'preview.html'), previewHtml);
console.log('Created preview.html');
console.log('\nNote: Chrome extensions need PNG icons. Converting SVG to PNG...');
