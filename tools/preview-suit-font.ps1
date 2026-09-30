Add-Type -AssemblyName System.Drawing
$p = $args[0]
if (-not $p) { $p = "D:\deepseekharness\charta-bridge\src\main\resources\assets\bridge\textures\font\suit.png" }
$b = [System.Drawing.Bitmap]::FromFile($p)
"size $($b.Width)x$($b.Height)"
$cellH = $b.Height
# The atlas is packed horizontally, so the cell width is not derivable from the height the way it
# used to be -- it is the generator's $cellW. Keep this in step with tools/make-suit-font.ps1.
$cellW = 13
$n = $b.Width / $cellW
for ($g = 0; $g -lt $n; $g++) {
    "--- glyph $g ---"
    # Both cells are odd (13x12), so the 2px block would need one column/row past the cell; stop
    # one short instead of clamping every sample.
    for ($y = 0; $y -lt $cellH - 1; $y += 2) {
        $l = ""
        for ($x = 0; $x -lt $cellW - 1; $x += 2) {
            $s = 0
            for ($dy = 0; $dy -lt 2; $dy++) { for ($dx = 0; $dx -lt 2; $dx++) { $s += $b.GetPixel($g * $cellW + $x + $dx, $y + $dy).A } }
            $a = $s / 4
            $l += if ($a -ge 200) { "#" } elseif ($a -ge 120) { "+" } elseif ($a -ge 45) { "-" } else { "." }
        }
        "  $l"
    }
}
$b.Dispose()
