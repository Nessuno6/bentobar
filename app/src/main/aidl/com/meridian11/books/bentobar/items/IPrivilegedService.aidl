package com.meridian11.books.bentobar.items;

interface IPrivilegedService {
    void destroy() = 16777114;
    int uid() = 1;
    String run(String command, int timeoutMs) = 2;
    String batteries() = 3;
}
