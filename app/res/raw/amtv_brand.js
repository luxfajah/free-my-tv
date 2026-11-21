// Branding knobs: the one file a private build swaps. Public build: our own icons and the system font.
//   icons  — glyph name → SVG markup used instead of the built-in set
//   font   — a font file served from the APK (assets/fonts/…) as https://music.apple.com/amtv/<file>
window.__amtvBrand = { icons: {}, font: null };
