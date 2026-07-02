# build phase not needed for static HTML
FROM nginx:alpine

# copy the config and index file
COPY nginx.conf /etc/nginx/nginx.conf
COPY index.html /usr/share/nginx/html/index.html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]