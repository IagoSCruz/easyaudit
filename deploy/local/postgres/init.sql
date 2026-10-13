-- Executado uma vez na criação do volume, como superusuário.
-- easyaudit_owner: dona do schema, roda o Flyway.
-- easyaudit_app: usada pela aplicação, sem BYPASSRLS e sem ser dona das tabelas.
CREATE ROLE easyaudit_owner LOGIN PASSWORD 'easyaudit_owner' NOBYPASSRLS;
CREATE ROLE easyaudit_app LOGIN PASSWORD 'easyaudit_app' NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;

ALTER DATABASE easyaudit OWNER TO easyaudit_owner;
GRANT CONNECT ON DATABASE easyaudit TO easyaudit_app;
