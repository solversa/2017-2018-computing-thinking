# -*- coding: utf-8 -*-
# 实时处理游戏信息

import json

DATABASE_FILE = 'game_database/game_status.db'
SGF_FILE_PATH = 'game_database/sgf/'


def is_exist_game(game):
    """
    检验游戏是否存在
    :param game: game结构体
    :return: TRUE / FALSE 
    """

    game_list = load_database_to_list(DATABASE_FILE)

    for cur_game in game_list:
        if game['game_id'] == cur_game['game_id']:
            return True

    return False


def can_join_game(game):
    """
    能否加入游戏
    :param game: game结构体
    :return: TRUE / FALSE 
    """
    game_list = load_database_to_list(DATABASE_FILE)

    for cur_game in game_list:
        if game['game_id'] == cur_game['game_id']:
            if cur_game['player_num'] == 1 and cur_game['status'] == 'wait':
                return True

    return False


def create_game(game):
    """
    创建游戏
    :param game: game结构体
    """
    game_list = load_database_to_list(DATABASE_FILE)

    new_game = {'game_id': game['game_id'], 'player1': game['user_id'], 'player_num': 1, 'status': 'wait'}
    game_list.append(new_game)

    write_list_to_database(game_list, DATABASE_FILE)


def join_game(game):
    """
    加入游戏
    :param game: game结构体
    """
    game_list = load_database_to_list(DATABASE_FILE)

    update_game = ''
    cur_game = ''
    for cur_game in game_list:
        if cur_game['game_id'] == game['game_id']:
            update_game = cur_game
            update_game['player_num'] = 2
            update_game['status'] = 'fight'
            update_game['player2'] = game['user_id']
            break

    game_list[game_list.index(cur_game)] = update_game

    f = open(DATABASE_FILE, 'w')
    f.write(json.dumps(game_list))
    f.close()


def get_game(game):
    """
    用于获取指定游戏id的具体对局信息
    :param game: game结构体
    :return: 具体对局信息
    """
    game_list = load_database_to_list(DATABASE_FILE)

    for cur_game in game_list:
        if cur_game['game_id'] == game['game_id']:
            return cur_game


def get_wait_game():
    """
    获取正在等待中的游戏列表
    :return: 
    """
    game_list = load_database_to_list(DATABASE_FILE)

    wait_game_list = []

    for cur_game in game_list:
        if cur_game['status'] == 'wait':
            wait_game_list.append(cur_game)
    result = ''
    for x in wait_game_list:
        result += '创建人ID: ' + x['player1'] + ' 游戏ID: ' + x['game_id'] + '<br>'
    return result


def load_database_to_list(DATABASE):
    """
    从数据库文件中加载list
    :param DATABASE: 
    :return: 
    """
    f = open(DATABASE, 'r')
    list_ = json.loads(f.read())
    f.close()
    return list_


def write_list_to_database(LIST, DATABASE):
    """
    将list写入数据库文件
    :param LIST: 
    :param DATABASE: 
    """
    f = open(DATABASE, 'w')
    f.write(json.dumps(LIST))
    f.close()