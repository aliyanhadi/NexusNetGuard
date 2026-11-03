package com.nexus.netguard.pro;

interface IShellService {
    // Transaction id required by Shizuku for the destroy() hook.
    void destroy() = 16777114;

    String exec(String command) = 1;
}
