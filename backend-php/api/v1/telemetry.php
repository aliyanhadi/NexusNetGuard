<?php
declare(strict_types=1);

require __DIR__ . '/../../lib/bootstrap.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Allow: POST');
    json_response(405, ['error' => 'method_not_allowed']);
}
require_token();

$raw = file_get_contents('php://input', false, null, 0, 4096);
$data = is_string($raw) ? json_decode($raw, true) : null;
if (!is_array($data)) {
    json_response(400, ['error' => 'invalid_json']);
}

$node = $data['node'] ?? null;
$ping = $data['ping_ms'] ?? null;
$jitter = $data['jitter_ms'] ?? null;
$loss = $data['loss_pct'] ?? null;
$version = $data['app_version'] ?? '';

$valid = is_string($node) && preg_match('/^[A-Za-z0-9 ._-]{1,64}$/', $node)
    && is_int($ping) && $ping >= 0 && $ping <= 10000
    && is_int($jitter) && $jitter >= 0 && $jitter <= 10000
    && is_int($loss) && $loss >= 0 && $loss <= 100
    && is_string($version) && preg_match('/^[0-9A-Za-z.+-]{0,24}$/', $version);

if (!$valid) {
    json_response(422, ['error' => 'validation_failed']);
}

try {
    // Anonymous by design: no IP address, device id or user id is stored.
    $stmt = db()->prepare(
        'INSERT INTO telemetry_samples (node, ping_ms, jitter_ms, loss_pct, app_version) VALUES (?, ?, ?, ?, ?)'
    );
    $stmt->execute([$node, $ping, $jitter, $loss, $version]);
} catch (Throwable $e) {
    error_log('telemetry.php: ' . $e->getMessage());
    json_response(503, ['error' => 'unavailable']);
}
json_response(204);
