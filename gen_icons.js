const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

function crc32(buf) {
    const table = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
        let c = i;
        for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        table[i] = c;
    }
    let crc = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
    return (crc ^ 0xFFFFFFFF) >>> 0;
}

function writeChunk(type, data) {
    const typeBytes = Buffer.from(type, 'ascii');
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const crcInput = Buffer.concat([typeBytes, data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(crcInput), 0);
    return Buffer.concat([len, typeBytes, data, crc]);
}

function createPNG(pixels, size) {
    const rows = [];
    for (let y = 0; y < size; y++) {
        const row = Buffer.alloc(1 + size * 4);
        row[0] = 0;
        for (let x = 0; x < size; x++) {
            const i = (y * size + x) * 4;
            row[1 + x * 4]     = pixels[i];
            row[1 + x * 4 + 1] = pixels[i + 1];
            row[1 + x * 4 + 2] = pixels[i + 2];
            row[1 + x * 4 + 3] = pixels[i + 3];
        }
        rows.push(row);
    }
    const raw = Buffer.concat(rows);
    const compressed = zlib.deflateSync(raw, { level: 9 });

    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(size, 0);
    ihdr.writeUInt32BE(size, 4);
    ihdr[8] = 8; ihdr[9] = 6; // bit depth 8, RGBA

    return Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        writeChunk('IHDR', ihdr),
        writeChunk('IDAT', compressed),
        writeChunk('IEND', Buffer.alloc(0)),
    ]);
}

function rect(pixels, size, r, g, b, a, x1, y1, x2, y2) {
    const rx1 = Math.round(x1), rx2 = Math.round(x2);
    const ry1 = Math.round(y1), ry2 = Math.round(y2);
    for (let y = ry1; y < ry2; y++) {
        for (let x = rx1; x < rx2; x++) {
            if (x < 0 || x >= size || y < 0 || y >= size) continue;
            const i = (y * size + x) * 4;
            // Alpha-blend over existing
            const srcA = a / 255;
            const dstA = pixels[i + 3] / 255;
            const outA = srcA + dstA * (1 - srcA);
            if (outA === 0) continue;
            pixels[i]     = Math.round((r * srcA + pixels[i]     * dstA * (1 - srcA)) / outA);
            pixels[i + 1] = Math.round((g * srcA + pixels[i + 1] * dstA * (1 - srcA)) / outA);
            pixels[i + 2] = Math.round((b * srcA + pixels[i + 2] * dstA * (1 - srcA)) / outA);
            pixels[i + 3] = Math.round(outA * 255);
        }
    }
}

function createBookIcon(size) {
    const pixels = new Uint8Array(size * size * 4);
    const S = size / 108;

    // Background: deep purple #4527A0
    rect(pixels, size, 69, 39, 160, 255, 0, 0, size, size);

    // Left page: white
    rect(pixels, size, 255, 255, 255, 255, 20*S, 26*S, 52*S, 84*S);
    // Right page: white
    rect(pixels, size, 255, 255, 255, 255, 56*S, 26*S, 88*S, 84*S);

    // Bottom binding: light grey
    rect(pixels, size, 200, 200, 200, 255, 18*S, 84*S, 90*S, 88*S);

    // Spine shadow
    rect(pixels, size, 0, 0, 0, 80, 50*S, 26*S, 52*S, 84*S);
    rect(pixels, size, 0, 0, 0, 80, 56*S, 26*S, 58*S, 84*S);

    // Text lines — left page
    for (const y of [38, 46, 54, 62])
        rect(pixels, size, 0, 0, 0, 80, 24*S, y*S, 48*S, (y+3)*S);
    rect(pixels, size, 0, 0, 0, 80, 24*S, 70*S, 38*S, 73*S);

    // Text lines — right page
    for (const y of [38, 46, 54, 62])
        rect(pixels, size, 0, 0, 0, 80, 60*S, y*S, 84*S, (y+3)*S);
    rect(pixels, size, 0, 0, 0, 80, 60*S, 70*S, 74*S, 73*S);

    return createPNG(pixels, size);
}

const densities = {
    'mipmap-mdpi':     48,
    'mipmap-hdpi':     72,
    'mipmap-xhdpi':    96,
    'mipmap-xxhdpi':   144,
    'mipmap-xxxhdpi':  192,
};

const resDir = path.join(__dirname, 'app', 'src', 'main', 'res');

for (const [density, size] of Object.entries(densities)) {
    const png = createBookIcon(size);
    const dir = path.join(resDir, density);

    // Remove old webp files
    for (const f of ['ic_launcher.webp', 'ic_launcher_round.webp']) {
        const p = path.join(dir, f);
        if (fs.existsSync(p)) fs.unlinkSync(p);
    }

    fs.writeFileSync(path.join(dir, 'ic_launcher.png'), png);
    fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), png);
    console.log(`${density} (${size}x${size}) done`);
}
console.log('All icons generated.');
