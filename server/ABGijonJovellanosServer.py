from roboflowoak import RoboflowOak
import asyncio
import websockets
import base64
import cv2
import numpy as np
import time
import math
import ssl
import os
import datetime
import csv
from queue import PriorityQueue
import datetime

if __name__ == '__main__':
    # instantiating an object (rf) with the RoboflowOak module
    rf = RoboflowOak(model="volleyball-tracking", confidence=0.5, overlap=0.5, version="18",
                     api_key="TIxhFuJstQkg29Nfm5Un", rgb=True, depth=True, device=None,
                     blocking=True)

    connected = set()
    client_choices = {}

    # Estructura de datos para almacenar las 10 mayores velocidades (velocidad, fecha, hora, nombre de imagen)
    top_speeds = PriorityQueue(maxsize=10)

    # Diámetro real del balón en metros
    diametro_real_metros = 0.0985

    prev_time = time.time()
    prev_position = None
    ball_speed_kps = None

    def load_from_csv(filename):
        try:
            with open(filename, 'r') as file:
                reader = csv.reader(file)
                next(reader)  # Saltar la cabecera
                for row in reader:
                    speed, timestamp, image_name = float(row[0]), row[1], row[2]
                    top_speeds.put((speed, timestamp, image_name))
        except FileNotFoundError:
            print(f"No se encontró el archivo '{filename}'. Se creará uno nuevo al detectar velocidades.")


    def write_to_csv():
        with open('top_speeds.csv', 'w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(['Speed (km/h)', 'Timestamp', 'Image Name'])
            # Convertir la cola de prioridad a una lista y ordenar de mayor a menor
            sorted_speeds = sorted(list(top_speeds.queue), reverse=True)
            for speed, timestamp, image_name in sorted_speeds:
                writer.writerow([speed, timestamp, image_name])


    # Función para actualizar la estructura con una nueva velocidad
    def update_top_speeds(speed_kph, timestamp, image_name):
        global top_speeds

        entry = (speed_kph, timestamp, image_name)

        if not top_speeds.full() or entry > top_speeds.queue[0]:
            if top_speeds.full():
                top_speeds.get()  # Eliminar la velocidad más baja si la cola está llena
            top_speeds.put(entry)
            write_to_csv()  # Escribir en CSV cada vez que se detecta una de las 10 mejores velocidades
            return True
        else:
            return False


    def draw_top_speeds_on_image(image):
        global top_speeds

        # Convertir la cola de prioridad a una lista y ordenar de mayor a menor
        sorted_speeds = sorted(list(top_speeds.queue), reverse=True)

        # Parámetros iniciales para el texto
        x, y, dy = 10, 30, 30  # Coordenadas iniciales y delta y para cada línea

        for i, (speed, timestamp, image_name) in enumerate(sorted_speeds, start=1):
            text = f"{i}. {speed:.2f} km/h"
            cv2.putText(image, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 255), 3)
            y += dy  # Actualizar la coordenada y para la siguiente velocidad

        return image

    async def echo(websocket, path):
        global connected, client_choices
        connected.add(websocket)
        client_choices[websocket] = 'BALL'
        try:
            # Escuchando los mensajes de los clientes
            async for message in websocket:
                # Aquí procesas el mensaje recibido
                print(f"Mensaje recibido: {message}")
                # Procesar el mensaje recibido
                # Almacenar la elección de red neuronal del cliente
                client_choices[websocket] = message.upper()
                await websocket.send("Cargando red neuronal para BALL")
        except websockets.exceptions.ConnectionClosed as e:
            print(f"Conexión cerrada: {e}")
        finally:
            connected.remove(websocket)
            del client_choices[websocket]  # Eliminar la elección cuando el cliente se desconecta


    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_context.load_cert_chain('fullchain.pem', 'privkey.pem')

    start_server = websockets.serve(echo, '0.0.0.0', 9999, ssl=ssl_context, ping_timeout=35)
    print("Servidor WebRTC iniciado en wss://localhost:9999")


    async def send_frame_data():
        global client_choices
        global rf
        if connected:
            for client in connected:
                # Obtener la elección de red neuronal del cliente
                choice = client_choices.get(client, None)

                # Procesar la imagen con la red neuronal adecuada
                if choice == "BALL":
                    frame = await process_with_ball_network()
                elif choice == "BEST":
                    frame = await process_with_person_network()
                else:
                    continue  # Si no hay una elección válida, no enviar nada

                # Enviar la imagen procesada
                _, buffer = cv2.imencode('.jpg', frame)
                frame_base64 = base64.b64encode(buffer).decode('utf-8')
                # print(f"Enviando frame: {frame}")
                await client.send(frame_base64)


    async def process_with_person_network():
        result, frame, raw_frame, depth = rf.detect()

        return frame


    async def process_with_ball_network():
        global max_speed_kph, max_speed_frame, max_speed_kph_formatted, rf_ball
        global prev_time, prev_position, ball_speed_kps

        t_init = time.time()
        result, frame, raw_frame, depth = rf.detect()
        height, width, channels = frame.shape

        predictions = result["predictions"]

        if predictions is not None and len(predictions) == 1 and predictions[0].json()['width'] > 10:
            ball_position = (predictions[0].json()['x'], predictions[0].json()['y'])
            ball_diameter_units = predictions[0].json()['width']

            # Calcular la escala de conversión (de unidades del modelo a metros)
            scale = diametro_real_metros / ball_diameter_units

            # Obtener la fecha y hora actual en el formato yyyymmddHHMMSS
            timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")

            if prev_position is not None:
                # Calcula la distancia Euclidiana entre las posiciones en unidades del modelo
                distance_units = math.sqrt(
                    (ball_position[0] - prev_position[0]) ** 2 + (ball_position[1] - prev_position[1]) ** 2)

                # Convertir la distancia a metros
                distance_meters = distance_units * scale

                # Convertir la distancia a kilómetros
                distance_kilometers = distance_meters / 1000

                # Calcula el tiempo transcurrido en segundos
                current_time = time.time()
                time_elapsed_seconds = current_time - prev_time

                # Calcula la velocidad en kilómetros por hora
                ball_speed_kph = distance_kilometers / (time_elapsed_seconds / 3600)

                ball_speed_kph_formatted = round(ball_speed_kph, 2)

                # Actualiza el tiempo y la posición para el próximo cálculo
                prev_time = current_time
                prev_position = ball_position

                ball_x, ball_y = int(ball_position[0]), int(ball_position[1])

                cv2.putText(raw_frame, f"{ball_speed_kph_formatted:.2f} km/h",
                            (ball_x - (int)(ball_diameter_units / 4), ball_y - (int)(ball_diameter_units / 2)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)
                cv2.circle(raw_frame, (ball_x, ball_y), (int)(ball_diameter_units / 2), (0, 255, 255),
                           2)  # Círculo amarillo

                ball_speed_kph_formatted = round(ball_speed_kph, 2)
                img_path = os.path.join('img', f'{timestamp}.png')
                if update_top_speeds(ball_speed_kph_formatted, timestamp, img_path):
                    # Guarda la imagen codificada en el archivo
                    cv2.imwrite(img_path, raw_frame)

                # print(f"Velocidad del balón: {ball_speed_kph_formatted} km/h")
            else:
                # Establece la posición inicial del balón
                prev_position = ball_position

        return draw_top_speeds_on_image(raw_frame)


    # Iniciar el servidor WebSocket
    asyncio.get_event_loop().run_until_complete(start_server)

    max_speed_kph_formatted = 0
    max_speed_kph = 0

    max_speed_frame = False

    load_from_csv('top_speeds.csv')

    while True:
        asyncio.get_event_loop().run_until_complete(send_frame_data())
