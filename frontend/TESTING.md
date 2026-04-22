# 快速测试指南

## 问题现象

访问知识库相关页面时，如果浏览器请求直接落到前端开发服务器，可能会出现类似 `No static resource api/ragent/knowledge-base` 的错误。

## 原因说明

前端开发环境需要通过 Vite 代理把 `/api` 请求转发到 Spring Boot 服务；如果代理没有生效，浏览器会把接口请求当成前端静态资源路径处理。

## 解决方式

确认 `vite.config.ts` 已配置代理：

```ts
server: {
  port: 5173,
  proxy: {
    "/api": {
      target: "http://localhost:8080",
      changeOrigin: true,
      secure: false,
    },
  },
}
```

## 测试步骤

### 1. 确认后端服务正常

```bash
curl http://localhost:8080/api/ragent/knowledge-base
```

如果返回未登录或鉴权相关 JSON，说明后端服务本身可用。

### 2. 启动前端开发服务

```bash
cd frontend
npm install
npm run dev
```

### 3. 打开前端页面

访问 `http://localhost:5173`。

### 4. 检查网络请求

打开浏览器开发者工具，在 `Network` 中确认接口请求：

- Request URL: `http://localhost:5173/api/ragent/knowledge-base`
- 代理目标: `http://localhost:8080/api/ragent/knowledge-base`

## 常见问题

### 1. 为什么 API 路径还是 `/api/ragent`

这是当前后端的上下文路径，属于运行时配置，和前端品牌名称分开处理。前端展示配置不会修改接口前缀，避免影响现有功能。

### 2. 代理修改后为什么不生效

Vite 在修改代理配置后需要重启开发服务器。

### 3. 接口返回 401 是否异常

不是异常。通常表示后端服务正常，只是当前请求还没有登录态。
