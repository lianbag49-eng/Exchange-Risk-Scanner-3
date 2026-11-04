// pm2 process file: `pm2 start deploy/ecosystem.config.cjs` from the arbitrage-bot directory.
// Starts in paper mode. For live trading add '--live' to args and ARB_LIVE_CONFIRM to arb.env, deliberately.
module.exports = {
  apps: [{
    name: 'arb-bot',
    cwd: __dirname + '/..',
    script: 'cli.mjs',
    node_args: '--env-file=deploy/arb.env',
    args: 'run --config config.json --feed ws',
    // A halt (one-legged fill, loss limit) exits cleanly and must stay stopped until a human runs `reset`,
    // so only restart on crashes, never in a loop.
    autorestart: true,
    stop_exit_codes: [0],
    max_restarts: 5,
    min_uptime: '60s',
    restart_delay: 10000,
    kill_timeout: 10000,
    time: true,
  }],
};
