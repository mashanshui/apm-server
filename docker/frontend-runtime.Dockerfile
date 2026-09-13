FROM nginx:1.27-alpine

# Nginx 同时提供 Vue history fallback、同源 API 代理和 Android 上报代理。
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/dist/ /usr/share/nginx/html/

EXPOSE 80
