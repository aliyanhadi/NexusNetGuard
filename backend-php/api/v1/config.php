<?php
declare(strict_types=1);

require __DIR__ . '/../../lib/bootstrap.php';

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'GET') {
    header('Allow: GET');
    json_response(405, ['error' => 'method_not_allowed']);
}
require_token();

$config = default_config();
try {
    $pdo = db();
    $nodes = $pdo->query('SELECT name, host, port FROM ping_nodes WHERE active = 1 ORDER BY sort_order, id')->fetchAll();
    $dns = $pdo->query('SELECT name, ip, private_dns FROM dns_servers WHERE active = 1 ORDER BY sort_order, id')->fetchAll();
    $hogs = $pdo->query('SELECT package_name FROM hog_packages ORDER BY package_name')->fetchAll(PDO::FETCH_COLUMN);

    if ($nodes) {
        $config['ping_nodes'] = array_map(
            static fn(array $r): array => ['name' => $r['name'], 'host' => $r['host'], 'port' => (int)$r['port']],
            $nodes
        );
    }
    if ($dns) {
        $config['dns_servers'] = $dns;
    }
    if ($hogs) {
        $config['hog_packages'] = $hogs;
    }
} catch (Throwable $e) {
    error_log('config.php: falling back to defaults: ' . $e->getMessage());
}

$body = json_encode($config, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
$etag = '"' . sha1((string)$body) . '"';
header('ETag: ' . $etag);
header('Cache-Control: public, max-age=300');
if (($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === $etag) {
    http_response_code(304);
    exit;
}
json_response(200, $config);
